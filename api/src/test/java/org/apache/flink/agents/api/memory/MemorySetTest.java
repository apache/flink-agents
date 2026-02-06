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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.memory.compaction.CompactionConfig;
import org.apache.flink.agents.api.prompt.Prompt;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MemorySetTest {
    @Test
    public void testJsonSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MemorySet memorySet =
                new MemorySet(
                        "test",
                        ChatMessage.class,
                        100,
                        new CompactionConfig("testModel", Prompt.fromText("Test prompt"), 100));
        String jsonValue = mapper.writeValueAsString(memorySet);

        MemorySet deserialized = mapper.readValue(jsonValue, MemorySet.class);
        Assertions.assertEquals(memorySet, deserialized);
    }
}
