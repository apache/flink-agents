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

package org.apache.flink.agents.api.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class ResourceDescriptorTest {
    @Test
    void pythonDeclarationRoundTripsWithoutWrapperMetadata() throws Exception {
        ResourceDescriptor descriptor =
                PythonResourceDescriptor.Builder.newBuilder("custom.models.Chat")
                        .addInitialArgument("model", "test")
                        .build();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(descriptor);
        ResourceDescriptor restored = mapper.readValue(json, ResourceDescriptor.class);
        Assertions.assertInstanceOf(PythonResourceDescriptor.class, restored);
        Assertions.assertEquals(descriptor, restored);
        Assertions.assertEquals("python", restored.getLanguage());
        Assertions.assertEquals("custom.models", restored.getModule());
        Assertions.assertEquals("Chat", restored.getClazz());
        Assertions.assertEquals(Map.of("model", "test"), restored.getInitialArguments());
    }

    @Test
    void invalidPythonClassNamesAreRejected() {
        for (String clazz : List.of("", "Chat", "module.", ".Chat")) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> PythonResourceDescriptor.Builder.newBuilder(clazz));
        }
    }

    @Test
    void javaDeclarationAlwaysSerializesLanguage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder("custom.models.Chat").build();
        Assertions.assertEquals(
                "java",
                mapper.readTree(mapper.writeValueAsString(descriptor)).get("language").asText());
        Assertions.assertEquals(
                descriptor,
                mapper.readValue(mapper.writeValueAsString(descriptor), ResourceDescriptor.class));
        Assertions.assertThrows(
                Exception.class,
                () ->
                        mapper.readValue(
                                "{\"language\":\"ruby\",\"target_module\":\"\",\"target_clazz\":\"Chat\",\"arguments\":{}}",
                                ResourceDescriptor.class));
    }

    @Test
    public void testResourceDescriptorSerializable() throws JsonProcessingException {
        Integer arg1 = 123;
        List<String> arg2 = List.of("1", "2", "3");
        Map<String, Map<String, Integer>> arg3 = Map.of("k1", Map.of("k2", 123));
        InputEvent arg4 = new InputEvent("input");

        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(Agent.class.getName())
                        .addInitialArgument("arg1", arg1)
                        .addInitialArgument("arg2", arg2)
                        .addInitialArgument("arg3", arg3)
                        .addInitialArgument("arg4", arg4)
                        .build();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(descriptor);
        ResourceDescriptor deserialized = mapper.readValue(json, ResourceDescriptor.class);
        Assertions.assertEquals(arg1, deserialized.getArgument("arg1"));
        Assertions.assertEquals(arg2, deserialized.getArgument("arg2"));
        Assertions.assertEquals(arg3, deserialized.getArgument("arg3"));
        Assertions.assertEquals(Agent.class.getName(), deserialized.getClazz());
    }
}
