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

    public static final ConfigOption<Boolean> TOOL_CALL_ASYNC =
            new ConfigOption<>("tool-call.async", Boolean.class, true);

    public static final ConfigOption<Boolean> TOOL_CALL_PARALLEL =
            new ConfigOption<>("tool-call.parallel", Boolean.class, true);

    public static final ConfigOption<Integer> TOOL_CALL_NUM_ASYNC_THREADS =
            new ConfigOption<>(
                    "tool-call.num-async-threads",
                    Integer.class,
                    Runtime.getRuntime().availableProcessors() * 2);

    public static final ConfigOption<Duration> TOOL_CALL_BATCH_TIMEOUT =
            new ConfigOption<>("tool-call.batch.timeout", Duration.class, Duration.ofMillis(-1));

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
