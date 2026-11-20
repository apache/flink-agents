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

package org.apache.flink.agents.resource.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Python declarations and YAML through plan JSON and Java providers to actual SDK settings. */
class OpenAIResourceDurationCrossLanguageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"direct", "yaml"})
    void pythonDurationReachesJavaSdk(String entry, @TempDir Path directory) throws Exception {
        Path root = projectRoot();
        Path output = directory.resolve("python.log");
        Process process =
                new ProcessBuilder(
                                System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"),
                                "-m",
                                "flink_agents.e2e_tests.e2e_tests_resource_cross_language.openai_resource_duration_fixture",
                                directory.toString(),
                                entry)
                        .directory(root.resolve("python").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("Python fixture completed")
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
        JsonNode cases = MAPPER.readTree(directory.resolve("cases.json").toFile());
        assertThat(cases.size()).isEqualTo(35);
        for (JsonNode testCase : cases) {
            String name = testCase.get("name").asText();
            AgentPlan plan = MAPPER.treeToValue(testCase.get("plan"), AgentPlan.class);
            // Include the Java plan JSON boundary used when a Python-authored job is deployed.
            plan = MAPPER.readValue(MAPPER.writeValueAsString(plan), AgentPlan.class);
            ResourceProvider provider =
                    plan.getResourceProviders()
                            .get(
                                    testCase.get("embedding").asBoolean()
                                            ? ResourceType.EMBEDDING_MODEL_CONNECTION
                                            : ResourceType.CHAT_MODEL_CONNECTION)
                            .get("connection");
            if (testCase.get("expected_millis").isNull()) {
                assertThatThrownBy(() -> provider.provide(null))
                        .as(name)
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasStackTraceContaining("timeout");
                continue;
            }
            Object connection = provider.provide(null);
            Field clientField = clientField(connection.getClass());
            clientField.setAccessible(true);
            OpenAIClient client = (OpenAIClient) clientField.get(connection);
            try {
                Field optionsField = client.getClass().getDeclaredField("clientOptions");
                optionsField.setAccessible(true);
                ClientOptions options = (ClientOptions) optionsField.get(client);
                Duration expected = Duration.ofMillis(testCase.get("expected_millis").asLong());
                assertThat(options.timeout().connect()).as(name + "/connect").isEqualTo(expected);
                assertThat(options.timeout().read()).as(name + "/read").isEqualTo(expected);
                assertThat(options.timeout().write()).as(name + "/write").isEqualTo(expected);
                assertThat(options.timeout().request()).as(name + "/request").isEqualTo(expected);
            } finally {
                client.close();
            }
        }
    }

    private static Field clientField(Class<?> clazz) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField("client");
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("client");
    }

    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.isDirectory(path.resolve("python/flink_agents"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
