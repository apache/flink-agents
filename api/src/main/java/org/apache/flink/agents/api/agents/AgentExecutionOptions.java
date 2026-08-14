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

package org.apache.flink.agents.api.agents;

import org.apache.flink.agents.api.configuration.ConfigOption;

public class AgentExecutionOptions {
    public static final ConfigOption<Agent.ErrorHandlingStrategy> ERROR_HANDLING_STRATEGY =
            new ConfigOption<>(
                    "error-handling-strategy",
                    Agent.ErrorHandlingStrategy.class,
                    Agent.ErrorHandlingStrategy.FAIL);

    public static final ConfigOption<Integer> MAX_RETRIES =
            new ConfigOption<>("max-retries", Integer.class, 3);

    public static final ConfigOption<Integer> RETRY_WAIT_INTERVAL =
            new ConfigOption<>("retry-wait-interval", Integer.class, 1);

    public static final ConfigOption<Integer> NUM_ASYNC_THREADS =
            new ConfigOption<>(
                    "num-async-threads",
                    Integer.class,
                    Runtime.getRuntime().availableProcessors() * 2);

    public static final ConfigOption<Boolean> CHAT_ASYNC =
            new ConfigOption<>("chat.async", Boolean.class, true);

    /** Whether the built-in tool-call action runs each tool via durable async execution. */
    public static final ConfigOption<Boolean> TOOL_CALL_ASYNC =
            new ConfigOption<>("tool-call.async", Boolean.class, true);

    /**
     * In-flight concurrency for tool calls from one {@code ToolRequestEvent} batch
     *
     * <p>{@code 1} runs tools serially. Values {@code > 1} run a parallel durable batch with a
     * sliding window of at most that many concurrent tool calls. On **Java**, concurrent in-batch
     * execution requires **JDK 21+** (Continuation API); below JDK 21 the batch still runs but tool
     * calls execute serially regardless of this setting.
     *
     * <p><b>Important:</b> the default is {@code availableProcessors()}, so multi-tool batches run
     * in parallel out of the box on most hosts (JDK 21+). Parallel tool batches use the same {@link
     * #NUM_ASYNC_THREADS} fixed thread pool as chat and RAG async work. That pool is created once
     * per operator subtask and shared by every key handled by that subtask — not a separate pool
     * per key. Built-in actions for one key run one at a time, so a chat async call and a tool
     * batch on the same key do not overlap in the usual chat → tool flow; contention shows up
     * mainly across keys on the same subtask (or when several keys each run large parallel
     * batches). With defaults ({@code num-async-threads = 2 × cores}, {@code tool-call.parallelism
     * = cores}), one batch can hold up to half of the pool; multiple busy keys can still saturate
     * it. Lower this value (for example {@code 1} or {@code 2}) or raise {@link #NUM_ASYNC_THREADS}
     * when mixing heavy tool batches with chat/RAG on hot subtasks.
     */
    public static final ConfigOption<Integer> TOOL_CALL_PARALLELISM =
            new ConfigOption<>(
                    "tool-call.parallelism",
                    Integer.class,
                    Runtime.getRuntime().availableProcessors());

    /**
     * Overall timeout for one parallel tool-call batch, in milliseconds.
     *
     * <p>Non-positive values disable the timeout. When the deadline elapses, unfinished slots are
     * failed; slots that already completed keep their success or failure outcome.
     *
     * <p><b>Thread reclamation:</b> the timeout unblocks the action but does not interrupt a tool
     * that is still running. {@code cancel(true)} cannot interrupt an in-flight {@code
     * CompletableFuture}, so a hung tool keeps its worker thread in the shared {@link
     * #NUM_ASYNC_THREADS} pool until it returns on its own. That thread is not reclaimed by the
     * timeout and stays unavailable to other keys on the same subtask, so a tool that never
     * returns permanently reduces pool capacity. Bound blocking work inside the tool itself (for
     * example an HTTP client read timeout) rather than relying on this batch timeout to free the
     * thread.
     */
    public static final ConfigOption<Long> TOOL_CALL_BATCH_TIMEOUT_MS =
            new ConfigOption<>("tool-call.batch.timeout.ms", Long.class, -1L);

    public static final ConfigOption<Boolean> RAG_ASYNC =
            new ConfigOption<>("rag.async", Boolean.class, true);

    /** Set to a positive value in milliseconds to enable short-term memory TTL; 0 disables it. */
    public static final ConfigOption<Long> SHORT_TERM_MEMORY_STATE_TTL_MS =
            new ConfigOption<>("short-term-memory.state-ttl.ms", Long.class, 0L);

    /** Update policy for short-term memory TTL, consulted only when TTL is enabled. */
    public static final ConfigOption<ShortTermMemoryTtlUpdate>
            SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE =
                    new ConfigOption<>(
                            "short-term-memory.state-ttl.update-type",
                            ShortTermMemoryTtlUpdate.class,
                            ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE);

    /**
     * Visibility policy for expired short-term memory state, consulted only when TTL is enabled.
     */
    public static final ConfigOption<ShortTermMemoryTtlVisibility>
            SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY =
                    new ConfigOption<>(
                            "short-term-memory.state-ttl.visibility",
                            ShortTermMemoryTtlVisibility.class,
                            ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED);
}
