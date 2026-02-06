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

public class LongTermMemoryOptions {
    public enum LongTermMemoryBackend {
        EXTERNAL_VECTOR_STORE("external_vector_store");

        private final String value;

        LongTermMemoryBackend(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /** The backend for long-term memory. */
    public static final ConfigOption<LongTermMemoryBackend> BACKEND =
            new ConfigOption<>("long-term-memory.backend", LongTermMemoryBackend.class, null);

    /** The name of the vector store to server as the backend for long-term memory. */
    public static final ConfigOption<String> EXTERNAL_VECTOR_STORE_NAME =
            new ConfigOption<>("long-term-memory.external-vector-store-name", String.class, null);

    /** Whether execute compaction asynchronously . */
    public static final ConfigOption<Boolean> ASYNC_COMPACTION =
            new ConfigOption<>("long-term-memory.async-compaction", Boolean.class, true);

    /** The thread count of executor for async compaction. */
    public static final ConfigOption<Integer> THREAD_COUNT =
            new ConfigOption<>("long-term-memory.async-compaction.thread-count", Integer.class, 16);
}
