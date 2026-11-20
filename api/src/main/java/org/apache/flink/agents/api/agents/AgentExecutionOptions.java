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

    /**
     * The most characters of one tool result that the built-in chat action sends to the model; a
     * longer result is cut in the middle. At least 100. Unset by default: tool results are sent in
     * full. The {@code context-management.*} options declare the agent's default {@link
     * org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy}.
     */
    public static final ConfigOption<Integer> CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS =
            new ConfigOption<>("context-management.tool-results.max-chars", Integer.class, null);

    /**
     * How many of the most recent tool-call rounds keep their tool results; the results of older
     * rounds are sent as a one line placeholder. At least 1. Unset by default: no result is masked.
     */
    public static final ConfigOption<Integer> CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS =
            new ConfigOption<>(
                    "context-management.tool-results.keep-last-rounds", Integer.class, null);

    /**
     * How many rounds are masked in one step, so that the messages before the masking boundary stay
     * unchanged between two steps. At least 1; 1 when unset. Only with {@link
     * #CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS}.
     */
    public static final ConfigOption<Integer> CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS =
            new ConfigOption<>(
                    "context-management.tool-results.mask-step-rounds", Integer.class, null);

    /**
     * The input budget of one model call in tokens. When the estimated input exceeds {@link
     * #CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO} of it, the older tool-call rounds of the
     * request are replaced by a summary. Unset by default: nothing is summarized.
     */
    public static final ConfigOption<Integer> CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS =
            new ConfigOption<>("context-management.max-input-tokens", Integer.class, null);

    /**
     * The share of {@link #CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS} above which older rounds are
     * summarized. Greater than 0 and at most 1; 0.8 when unset.
     */
    public static final ConfigOption<Double> CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO =
            new ConfigOption<>(
                    "context-management.summarization.trigger-ratio", Double.class, null);

    /**
     * How many of the most recent rounds a summarization leaves as they are. At least 1; 3 when
     * unset.
     */
    public static final ConfigOption<Integer> CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS =
            new ConfigOption<>(
                    "context-management.summarization.keep-last-rounds", Integer.class, null);

    /**
     * The chat model resource that writes summaries. When unset, the chat model that answers the
     * request writes them.
     */
    public static final ConfigOption<String> CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL =
            new ConfigOption<>("context-management.summarization.model", String.class, null);
}
