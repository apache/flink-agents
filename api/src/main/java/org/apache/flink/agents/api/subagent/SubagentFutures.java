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

package org.apache.flink.agents.api.subagent;

import java.util.List;

/**
 * Several sub-agent handles resolved together, in submission order.
 *
 * <p>Each handle resolves itself when its wait starts; each invocation keeps its own durable slot,
 * so a partially completed batch replays the parts that finished.
 *
 * <pre>{@code
 * List<Result> results = first.combine(second, third).awaitAll();
 * }</pre>
 *
 * <p>A batch is not an invocation: it carries no {@code (sessionId, callId)} identity and is not a
 * {@link SubagentFuture}, so it resolves only through {@link #awaitAll()}.
 *
 * <p>Abstract data structure; the implementations live in the runtime layer.
 */
public abstract class SubagentFutures {

    /** Whether every handle in the batch has reached a terminal state. */
    public abstract boolean isDone();

    /**
     * Waits for every handle in submission order; each handle resolves itself when its wait starts.
     * Each invocation keeps its own durable slot, so a partially completed batch replays the parts
     * that finished. Like {@link SubagentFuture#await()}, the waits release the mailbox.
     */
    public abstract List<Result> awaitAll() throws Exception;

    /**
     * Requests cancellation of every handle in the batch. The default implementation does nothing;
     * the cancellation semantics are defined by the concrete implementation.
     */
    public void cancel() {}

    /** Adds more handles to the batch. */
    public abstract SubagentFutures combine(SubagentFuture... others);
}
