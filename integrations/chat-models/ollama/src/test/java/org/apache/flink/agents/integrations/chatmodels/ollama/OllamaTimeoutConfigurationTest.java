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
package org.apache.flink.agents.integrations.chatmodels.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.api.yaml.spec.DescriptorSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks numeric timeouts through declarations and SDK request configuration. */
class OllamaTimeoutConfigurationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void absentTimeoutUsesDefault() throws Exception {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        OllamaChatModelConnection connection = new OllamaChatModelConnection(descriptor, null);
        try {
            assertThat(sdkTimeout(connection)).isEqualTo(60);
        } finally {
            connection.close();
        }
    }

    @Test
    void directAndJsonDeclarationsReachSdk() throws Exception {
        for (Object value :
                Arrays.asList(
                        null,
                        1,
                        60L,
                        new BigDecimal("1500.0"),
                        (long) Integer.MAX_VALUE + 1,
                        Long.MAX_VALUE)) {
            ResourceDescriptor descriptor = descriptor(value);
            long expected = value == null ? 60 : ((Number) value).longValue();
            for (ResourceDescriptor input :
                    List.of(
                            descriptor,
                            JSON.readValue(
                                    JSON.writeValueAsString(descriptor),
                                    ResourceDescriptor.class))) {
                OllamaChatModelConnection connection = new OllamaChatModelConnection(input, null);
                try {
                    assertThat(sdkTimeout(connection)).as("timeout %s", value).isEqualTo(expected);
                } finally {
                    connection.close();
                }
            }
        }
    }

    @Test
    void rejectsInvalidNumericValues() {
        for (Object value :
                List.of(
                        -1,
                        -0.5,
                        1.5,
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        "60",
                        true,
                        new BigInteger("9223372036854775808"),
                        new BigDecimal("1.0000000000000000001"),
                        0)) {
            assertThatThrownBy(() -> new OllamaChatModelConnection(descriptor(value), null))
                    .as("timeout %s", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestTimeout");
        }
    }

    @Test
    void yamlNumericTypesReachSdk() throws Exception {
        for (long value : new long[] {60, 2147483648L}) {
            String yaml =
                    "name: connection\nclazz: "
                            + OllamaChatModelConnection.class.getName()
                            + "\ntype: java\nendpoint: http://localhost:11434\nrequestTimeout: "
                            + value;
            DescriptorSpec spec =
                    new ObjectMapper(new YAMLFactory()).readValue(yaml, DescriptorSpec.class);
            OllamaChatModelConnection connection =
                    new OllamaChatModelConnection(
                            YamlLoader.buildDescriptor(spec, ResourceType.CHAT_MODEL_CONNECTION),
                            null);
            try {
                assertThat(sdkTimeout(connection)).isEqualTo(value);
            } finally {
                connection.close();
            }
        }
    }

    private static ResourceDescriptor descriptor(Object timeout) {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", timeout)
                .build();
    }

    private static long sdkTimeout(OllamaChatModelConnection connection) throws Exception {
        Object caller = field(connection, "caller");
        Method builder =
                caller.getClass()
                        .getSuperclass()
                        .getDeclaredMethod("getRequestBuilderDefault", URI.class);
        builder.setAccessible(true);
        HttpRequest request =
                ((HttpRequest.Builder)
                                builder.invoke(
                                        caller, URI.create("http://localhost:11434/api/chat")))
                        .build();
        return request.timeout().orElseThrow().getSeconds();
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue with inherited SDK fields.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
