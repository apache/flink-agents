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
package org.apache.flink.agents.api.memory;

import org.apache.flink.agents.api.configuration.ConfigOption;

/** Config options for long-term memory. */
public class LongTermMemoryOptions {

    /** Config options for the Mem0-based long-term memory backend. */
    public static class Mem0 {
        public static final ConfigOption<String> CHAT_MODEL_SETUP =
                new ConfigOption<>("long-term-memory.mem0.chat-model-setup", String.class, null);

        public static final ConfigOption<String> EMBEDDING_MODEL_SETUP =
                new ConfigOption<>(
                        "long-term-memory.mem0.embedding-model-setup", String.class, null);

        public static final ConfigOption<String> VECTOR_STORE =
                new ConfigOption<>("long-term-memory.mem0.vector-store", String.class, null);
    }
}
