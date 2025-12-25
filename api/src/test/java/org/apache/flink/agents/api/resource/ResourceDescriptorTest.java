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
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class ResourceDescriptorTest {
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
