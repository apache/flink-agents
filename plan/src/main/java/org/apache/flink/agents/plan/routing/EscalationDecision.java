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

package org.apache.flink.agents.plan.routing;

import javax.annotation.Nullable;

/**
 * The decision to escalate a round: the tier that answered, the tier the request moves to, the
 * signal that rejected the answer and its reason, with the signal's score when it gave one. {@link
 * EscalationEvaluator} produces it and {@link ModelRoutingExecutor} acts on it.
 */
public final class EscalationDecision {
    public final String fromTier;
    public final String toTier;
    public final String signal;
    public final String reason;
    @Nullable public final Double score;

    EscalationDecision(
            String fromTier, String toTier, String signal, String reason, @Nullable Double score) {
        this.fromTier = fromTier;
        this.toTier = toTier;
        this.signal = signal;
        this.reason = reason;
        this.score = score;
    }
}
