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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ResourceDescriptorTest {
    @Test
    void preservesDescriptorAndUsesDefaultForMissingOrNullValues() {
        Duration value = Duration.ofNanos(1_000);
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder("example.Connection")
                        .addInitialArgument("timeout", value)
                        .addInitialArgument("null", null)
                        .build();
        assertThat(descriptor.getDurationArgument("timeout", Duration.ZERO)).isSameAs(value);
        assertThat(descriptor.<Object>getArgument("timeout")).isSameAs(value);
        assertThat(descriptor.getDurationArgument("missing", value)).isSameAs(value);
        assertThat(descriptor.getDurationArgument("null", null)).isNull();
    }

    @Test
    void parseErrorsIdentifyArgumentAndRetainCause() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder("example.Connection")
                        .addInitialArgument("request_timeout", "invalid")
                        .build();
        assertThatThrownBy(() -> descriptor.getDurationArgument("request_timeout", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request_timeout")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durationArgumentsSerializeAsIsoStringsWithoutChangingOtherArguments() throws Exception {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder("custom.Connection")
                        .addInitialArgument("timeout", Duration.ofNanos(1_500_000))
                        .addInitialArgument("retries", 3)
                        .addInitialArgument("headers", Map.of("x-test", "value"))
                        .addInitialArgument("optional", null)
                        .build();
        ObjectMapper mapper = new ObjectMapper();
        ResourceDescriptor restored =
                mapper.readValue(mapper.writeValueAsString(descriptor), ResourceDescriptor.class);
        Assertions.assertEquals("PT0.0015S", restored.getArgument("timeout"));
        assertThat(restored.getDurationArgument("timeout", null))
                .isEqualTo(Duration.ofNanos(1_500_000));
        Assertions.assertEquals(Integer.valueOf(3), restored.getArgument("retries"));
        Assertions.assertEquals(Map.of("x-test", "value"), restored.getArgument("headers"));
        Assertions.assertNull(restored.getArgument("optional"));
        Assertions.assertEquals(Duration.ofNanos(1_500_000), descriptor.getArgument("timeout"));
    }

    @Test
    void durationPrecisionIsCheckedBeforeAndAfterSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder("example.Connection")
                        .addInitialArgument("timeout", Duration.ofNanos(1))
                        .build();
        ResourceDescriptor restored =
                mapper.readValue(mapper.writeValueAsString(descriptor), ResourceDescriptor.class);
        for (ResourceDescriptor input : List.of(descriptor, restored)) {
            assertThatThrownBy(() -> input.getDurationArgument("timeout", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout")
                    .hasMessageContaining("finer than 1 us");
        }
    }

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
