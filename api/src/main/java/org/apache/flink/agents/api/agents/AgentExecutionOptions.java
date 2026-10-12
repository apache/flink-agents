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

    public static final ConfigOption<Integer> MAX_RETRIES =
            new ConfigOption<>("max-retries", Integer.class, 0);

    public static final ConfigOption<Integer> RETRY_WAIT_INTERVAL =
            new ConfigOption<>("retry-wait-interval", Integer.class, 1);

    public static final ConfigOption<Integer> NUM_ASYNC_THREADS =
            new ConfigOption<>(
                    "num-async-threads",
                    Integer.class,
                    Runtime.getRuntime().availableProcessors() * 2);

    /**
     * Experimental fallback switch for the JDK&lt;21 parallel execution engine. Only consulted for
     * pure-Java agents without coroutine support; the JDK 21 coroutine engine and plans containing
     * Python actions never use the parallel engine regardless of this value (no Python-side
     * counterpart exists by design). Set to {@code false} to fall back to the serial engine.
     */
    public static final ConfigOption<Boolean> PARALLEL_EXECUTION_ENABLED =
            new ConfigOption<>("parallel-execution.enabled", Boolean.class, true);

    /**
     * Maximum number of input records that may be in flight concurrently. Only enforced by the
     * JDK&lt;21 parallel execution engine for pure-Java agents; the JDK 21 coroutine engine and
     * plans containing Python actions ignore it. Every admitted record consumes one unit of budget;
     * at the cap, admission of further records blocks until an in-flight record retires.
     */
    public static final ConfigOption<Integer> MAX_IN_FLIGHT_INPUT_RECORDS =
            new ConfigOption<>("max-in-flight-input-records", Integer.class, 100);

    public static final ConfigOption<Boolean> CHAT_ASYNC =
            new ConfigOption<>("chat.async", Boolean.class, true);

    /** Whether the built-in tool-call action runs each tool via durable async execution. */
    public static final ConfigOption<Boolean> TOOL_CALL_ASYNC =
            new ConfigOption<>("tool-call.async", Boolean.class, true);

    /**
     * Maximum in-flight calls in one async batch composed with RunnerContext.gather, including
     * ordinary, durable, and built-in tool calls.
     *
     * <p>The default is {@code availableProcessors()}; {@code 1} runs calls serially. The shared
     * {@link #NUM_ASYNC_THREADS} pool also limits actual concurrency. Lower this per-batch limit to
     * leave capacity for other calls on the same operator subtask.
     */
    public static final ConfigOption<Integer> ASYNC_BATCH_PARALLELISM =
            new ConfigOption<>(
                    "async.batch.parallelism",
                    Integer.class,
                    Runtime.getRuntime().availableProcessors());

    /**
     * Overall timeout for one async batch composed with RunnerContext.gather, in milliseconds.
     * Applies to ordinary, durable, and built-in tool calls. Non-positive values disable it.
     *
     * <p>Completed calls keep their outcomes; unfinished calls receive timeout failures. For
     * durable calls, started calls are finalized as failures while unstarted calls remain pending
     * for recovery.
     *
     * <p>A timeout cannot interrupt an already-running callback or undo its external effects. The
     * callback occupies its worker until it returns, so blocking operations should also set their
     * own timeouts, such as an HTTP client read timeout.
     */
    public static final ConfigOption<Long> ASYNC_BATCH_TIMEOUT_MS =
            new ConfigOption<>("async.batch.timeout.ms", Long.class, -1L);

    public static final ConfigOption<Boolean> RAG_ASYNC =
            new ConfigOption<>("rag.async", Boolean.class, true);

    /** Opt-in lifecycle event emitted at the beginning of each agent run. */
    public static final ConfigOption<Boolean> AGENT_RUN_BEGIN_EVENT =
            new ConfigOption<>("agent-run.begin-event", Boolean.class, false);

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
