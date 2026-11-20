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

import java.time.Duration;

public class AgentExecutionOptions {

    public static final ConfigOption<Integer> MAX_RETRIES =
            new ConfigOption<>("max-retries", Integer.class, 0);

    /**
     * Initial wait between retries controlled by {@link #MAX_RETRIES}, doubled for each subsequent
     * retry. The interval is truncated to milliseconds. Non-positive values and values below 1 ms
     * retry without waiting.
     */
    public static final ConfigOption<Duration> RETRY_WAIT_INTERVAL =
            new ConfigOption<>("retry-wait-interval", Duration.class, Duration.ofSeconds(1));

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
     * Maximum number of input records that may be in flight concurrently on each operator subtask.
     * Every admitted record, including one queued behind another record for the same key, consumes
     * one unit of budget; at the cap, admission of further records blocks until an in-flight record
     * retires.
     */
    public static final ConfigOption<Integer> MAX_IN_FLIGHT_INPUT_RECORDS =
            new ConfigOption<>("max-in-flight-input-records", Integer.class, 100);

    public static final ConfigOption<Boolean> CHAT_ASYNC =
            new ConfigOption<>("chat.async", Boolean.class, true);

    /** Whether the built-in decision-model action uses durable asynchronous execution. */
    public static final ConfigOption<Boolean> DECISION_ASYNC =
            new ConfigOption<>("decision.async", Boolean.class, true);

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
     * Overall timeout for one {@code RunnerContext.gather} batch, with microsecond precision. A
     * non-positive duration disables the timeout. Completed results are retained; unfinished calls
     * receive timeout failures. A timeout cannot interrupt running callbacks or undo their external
     * effects.
     */
    public static final ConfigOption<Duration> ASYNC_BATCH_TIMEOUT =
            new ConfigOption<>("async.batch.timeout", Duration.class, Duration.ofMillis(-1));

    public static final ConfigOption<Boolean> RAG_ASYNC =
            new ConfigOption<>("rag.async", Boolean.class, true);

    /** Opt-in lifecycle event emitted at the beginning of each agent run. */
    public static final ConfigOption<Boolean> AGENT_RUN_BEGIN_EVENT =
            new ConfigOption<>("agent-run.begin-event", Boolean.class, false);

    /**
     * Short-term memory time-to-live. Non-positive values disable expiration. Positive values must
     * be at least 1 ms and are truncated to milliseconds.
     */
    public static final ConfigOption<Duration> SHORT_TERM_MEMORY_STATE_TTL =
            new ConfigOption<>("short-term-memory.state-ttl", Duration.class, Duration.ZERO);

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

    public static final ConfigOption<Integer> LOOP_MAX_TURNS =
            new ConfigOption<>("react-loop.budget.max-turns", Integer.class, null);
    public static final ConfigOption<Long> LOOP_MAX_TOKENS =
            new ConfigOption<>("react-loop.budget.max-tokens", Long.class, null);
    public static final ConfigOption<Duration> LOOP_MAX_TIME =
            new ConfigOption<>("react-loop.budget.max-time", Duration.class, null);
    public static final ConfigOption<ReActLoopBudget.BudgetMode> LOOP_BUDGET_MODE =
            new ConfigOption<>(
                    "react-loop.budget.mode",
                    ReActLoopBudget.BudgetMode.class,
                    ReActLoopBudget.BudgetMode.SOFT);
}
