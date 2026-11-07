/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.agents.api.chat.model.routing;

/**
 * A user-defined quality signal for {@link Escalation}: judges one model round and decides whether
 * the request should move to the next tier.
 *
 * <p>Implementations are declared by class name ({@link QualitySignals#custom(Class)}), must have a
 * public no-argument or {@code (Map)} constructor, and are instantiated by the engine. The engine
 * calls {@link #evaluate} after each model round of a tiered request and before any tool call from
 * that round is dispatched; the signal sees the request, the response and the candidate's tools
 * through {@link QualityContext}, and judges from that alone. The call runs inside the engine's
 * durable record for the round, so a completed evaluation is replayed on recovery rather than
 * re-run; this is also why a signal gets no runner context: a durable call, an event or a memory
 * write of its own would not be replayed with it. A judgment that needs a model is declared as a
 * decision-model signal ({@link QualitySignals#decision}), which the engine runs durably with
 * retries and metrics. Throwing fails the request; a signal that cannot judge an answer should
 * accept it.
 *
 * <p>Structural checks (an empty answer, a call to a tool the candidate does not have, arguments
 * that fail the tool's schema, a tool call repeated with identical arguments) run before custom
 * signals and need no implementation here.
 */
public interface QualitySignal {

    /**
     * Judge one model round.
     *
     * @param context the request, the candidate's response, its tools and the tier position
     * @return {@link QualityVerdict#accept()} to keep the answer, or an escalating verdict
     * @throws Exception when the signal cannot run; the request fails
     */
    QualityVerdict evaluate(QualityContext context) throws Exception;
}
