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

    // Async execution is supported on jdk >= 21, so set default false here.
    public static final ConfigOption<Boolean> CHAT_ASYNC =
            new ConfigOption<>("chat.async", Boolean.class, true);

    public static final ConfigOption<Boolean> TOOL_CALL_ASYNC =
            new ConfigOption<>("tool-call.async", Boolean.class, true);

    public static final ConfigOption<Boolean> RAG_ASYNC =
            new ConfigOption<>("rag.async", Boolean.class, true);
}
