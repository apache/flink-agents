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
package org.apache.flink.agents.api.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryRefJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesMemoryRef() throws Exception {
        MemoryRef original = MemoryRef.create(MemoryObject.MemoryType.SENSORY, "memory.path");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        MemoryRef restored = objectMapper.readValue(json, MemoryRef.class);

        assertEquals("memory_ref", node.get("@type").asText());
        assertEquals("sensory", node.get("memory_type").asText());
        assertEquals("memory.path", node.get("path").asText());
        assertEquals(original, restored);
        assertEquals(MemoryObject.MemoryType.SENSORY, restored.getType());
    }

    @Test
    void rejectsInvalidMemoryRef() throws JsonProcessingException {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        objectMapper.readValue(
                                "{\"memory_type\":\"unknown\",\"path\":\"memory.path\"}",
                                MemoryRef.class),
                "No enum constant org.apache.flink.agents.api.context.MemoryObject.MemoryType.UNKNOWN");
    }
}
