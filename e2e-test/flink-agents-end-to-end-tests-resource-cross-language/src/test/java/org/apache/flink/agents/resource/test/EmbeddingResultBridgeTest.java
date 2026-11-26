/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.embedding.model.EmbeddingTokenUsage;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.resource.python.PythonEmbeddingModelConnection;
import org.apache.flink.agents.plan.resource.python.PythonEmbeddingModelSetup;
import org.apache.flink.agents.runtime.python.utils.JavaResourceAdapter;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;
import pemja.core.object.PyObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises both embedding bridges without a model server. */
public class EmbeddingResultBridgeTest {
    private static final ResourceDescriptor DESCRIPTOR =
            new ResourceDescriptor(
                    "fixture", Map.of("connection", "connection", "model", "fixture"));

    @Test
    void preservesShapeAndOptionalUsageForConnectionsAndSetups() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("python/flink_agents"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        PythonInterpreter interpreter = new PythonInterpreter(config);
        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(interpreter, () -> new PythonInterpreter(config))) {
            interpreter.exec("from flink_agents.runtime import python_java_utils");
            interpreter.exec(
                    "from flink_agents.runtime.tests import embedding_result_bridge as bridge");
            PythonResourceAdapterImpl adapter = new PythonResourceAdapterImpl(null, manager, null);
            PythonEmbeddingModelConnection connection =
                    new PythonEmbeddingModelConnection(
                            adapter,
                            (PyObject) interpreter.invoke("bridge.create_model", false),
                            DESCRIPTOR,
                            null);
            PythonEmbeddingModelSetup setup =
                    new PythonEmbeddingModelSetup(
                            adapter,
                            (PyObject) interpreter.invoke("bridge.create_model", true),
                            DESCRIPTOR,
                            null);
            try {
                for (boolean usage : new boolean[] {false, true}) {
                    Map<String, Object> params = Map.of("dimension", 1, "report_usage", usage);
                    assertSingle(connection.embed("abc", params), 3.0f, usage);
                    assertSingle(setup.embed("abc", params), 3.0f, usage);
                    ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
                    assertSingle(connection.embed(image, params), 9.0f, usage);
                    assertSingle(setup.embed(image, params), 9.0f, usage);
                    assertBatch(
                            connection.embed(List.of(new TextBlock("abc")), params),
                            List.of(3.0f),
                            usage);
                    assertBatch(
                            setup.embed(List.of(new TextBlock("abc")), params),
                            List.of(3.0f),
                            usage);
                    List<DataContentBlock> blocks = List.of(new TextBlock("abc"), image);
                    assertBatch(connection.embed(blocks, params), List.of(3.0f, 9.0f), usage);
                    assertBatch(setup.embed(blocks, params), List.of(3.0f, 9.0f), usage);
                    assertBatch(connection.embed(List.of(), params), List.of(), usage);
                    assertBatch(setup.embed(List.of(), params), List.of(), usage);
                }
                assertSingle(setup.embed("abc"), 3.0f, true);
            } finally {
                setup.close();
                connection.close();
            }

            JavaResourceAdapter javaAdapter =
                    new JavaResourceAdapter(null, getClass().getClassLoader());
            assertThat(
                            interpreter.invoke(
                                    "bridge.check_java_model",
                                    new FixtureConnection(),
                                    javaAdapter,
                                    false))
                    .isEqualTo(true);
            assertThat(
                            interpreter.invoke(
                                    "bridge.check_java_model",
                                    new FixtureSetup(),
                                    javaAdapter,
                                    true))
                    .isEqualTo(true);
        }
    }

    private static void assertSingle(EmbeddingResult<float[]> result, float value, boolean usage) {
        assertThat(result.getEmbeddings()).containsExactly(value);
        assertUsage(result, usage);
    }

    private static void assertBatch(
            EmbeddingResult<List<float[]>> result, List<Float> values, boolean usage) {
        assertThat(result.getEmbeddings()).hasSize(values.size());
        for (int i = 0; i < values.size(); i++) {
            assertThat(result.getEmbeddings().get(i)).containsExactly(values.get(i));
        }
        assertUsage(result, usage);
    }

    private static void assertUsage(EmbeddingResult<?> result, boolean usage) {
        if (usage) {
            assertThat(result.getTokenUsage()).isNotNull();
            assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(7);
            assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(9);
        } else {
            assertThat(result.getTokenUsage()).isNull();
        }
    }

    /** Public so Pemja can invoke it through the Java wrappers. */
    public static class FixtureConnection extends BaseEmbeddingModelConnection {
        public FixtureConnection() {
            super(DESCRIPTOR, null);
        }

        @Override
        public EmbeddingResult<List<float[]>> embed(
                List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
            return new EmbeddingResult<>(
                    blocks.stream()
                            .map(
                                    b ->
                                            new float[] {
                                                b instanceof TextBlock
                                                        ? ((TextBlock) b).getText().length()
                                                        : 9
                                            })
                            .collect(Collectors.toList()),
                    usage(parameters));
        }

        private EmbeddingTokenUsage usage(Map<String, Object> parameters) {
            assertThat(((Number) parameters.get("dimension")).intValue()).isEqualTo(1);
            return Boolean.FALSE.equals(parameters.get("report_usage"))
                    ? null
                    : new EmbeddingTokenUsage(7, 9);
        }
    }

    /** Uses the production setup forwarding and parameter merge implementation. */
    public static class FixtureSetup extends BaseEmbeddingModelSetup {
        public FixtureSetup() {
            super(DESCRIPTOR, null);
            connection = new FixtureConnection();
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("dimension", 1, "report_usage", true);
        }
    }
}
