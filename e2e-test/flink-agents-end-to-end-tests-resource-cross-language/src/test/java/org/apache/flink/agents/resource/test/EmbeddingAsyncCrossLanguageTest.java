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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.embedding.model.EmbeddingTokenUsage;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Full runtime coverage for sync and concurrent embedding resource calls across languages. */
public class EmbeddingAsyncCrossLanguageTest {
    private static final String PYTHON_MODULE =
            "flink_agents.e2e_tests.e2e_tests_resource_cross_language.embedding_async_cross_language_agent";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void javaAgentCallsPythonEmbeddingResources(boolean async) throws Exception {
        Configuration config = new Configuration();
        config.setString(
                "python.executable", System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"));
        config.setString("python.pythonpath", System.getenv().getOrDefault("PYTHONPATH", ""));
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        AgentsExecutionEnvironment agents = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agents.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);
        agents.getConfig().set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        DataStream<Object> output =
                agents.fromDataStream(
                                env.fromData(async), (KeySelector<Boolean, String>) value -> "key")
                        .apply(new PythonEmbeddingAgent())
                        .toDataStream();
        try (CloseableIterator<Object> results = output.collectAsync()) {
            agents.execute();
            assertThat(results.hasNext()).isTrue();
            assertThat(results.next()).isEqualTo("passed");
            assertThat(results.hasNext()).isFalse();
        }
    }

    public static class PythonEmbeddingAgent extends Agent {
        @EmbeddingModelConnection
        public static ResourceDescriptor connection() {
            return PythonResourceDescriptor.Builder.newBuilder(PYTHON_MODULE + ".BarrierConnection")
                    .build();
        }

        @EmbeddingModelSetup
        public static ResourceDescriptor model() {
            return PythonResourceDescriptor.Builder.newBuilder(PYTHON_MODULE + ".BarrierSetup")
                    .addInitialArgument("connection", "connection")
                    .addInitialArgument("model", "fixture")
                    .build();
        }

        @Action(EventType.InputEvent)
        public static void embed(Event event, RunnerContext ctx) throws Exception {
            boolean async = (Boolean) InputEvent.fromEvent(event).getInput();
            BaseEmbeddingModelConnection connection =
                    (BaseEmbeddingModelConnection)
                            ctx.getResource("connection", ResourceType.EMBEDDING_MODEL_CONNECTION);
            BaseEmbeddingModelSetup model =
                    (BaseEmbeddingModelSetup)
                            ctx.getResource("model", ResourceType.EMBEDDING_MODEL);
            ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
            List<Object> inputs =
                    List.of(
                            "abc",
                            new TextBlock("abc"),
                            image,
                            List.of(new TextBlock("abc")),
                            List.of(new TextBlock("abc"), image),
                            List.of());
            List<List<Float>> expected =
                    List.of(
                            List.of(3f),
                            List.of(3f),
                            List.of(9f),
                            List.of(3f),
                            List.of(3f, 9f),
                            List.of());
            for (boolean usage : new boolean[] {false, true}) {
                Map<String, Object> parameters =
                        Map.of("dimension", 1, "report_usage", usage, "synchronize", async);
                for (int i = 0; i < inputs.size(); i++) {
                    Object input = inputs.get(i);
                    Callable<EmbeddingResult<?>> callConnection =
                            () -> invoke(connection, input, parameters);
                    Callable<EmbeddingResult<?>> callSetup = () -> invoke(model, input, parameters);
                    List<EmbeddingResult<?>> results;
                    if (async) {
                        List<Outcome<EmbeddingResult<?>>> outcomes =
                                ctx.gather(
                                                List.of(
                                                        ctx.executeAsync(callConnection),
                                                        ctx.executeAsync(callSetup)))
                                        .await();
                        for (Outcome<?> outcome : outcomes) {
                            assertThat(outcome.getError()).isNull();
                        }
                        results =
                                outcomes.stream()
                                        .map(Outcome::getValue)
                                        .collect(Collectors.toList());
                    } else {
                        results = List.of(callConnection.call(), callSetup.call());
                    }
                    for (EmbeddingResult<?> result : results) {
                        if (input instanceof List) {
                            List<?> vectors = (List<?>) result.getEmbeddings();
                            assertThat(vectors).hasSize(expected.get(i).size());
                            for (int j = 0; j < vectors.size(); j++) {
                                assertThat((float[]) vectors.get(j))
                                        .containsExactly(expected.get(i).get(j));
                            }
                        } else {
                            assertThat((float[]) result.getEmbeddings())
                                    .containsExactly(expected.get(i).get(0));
                        }
                        if (usage) {
                            assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(7);
                            assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(9);
                        } else {
                            assertThat(result.getTokenUsage()).isNull();
                        }
                    }
                }
            }
            ctx.sendEvent(new OutputEvent("passed"));
        }
    }

    @SuppressWarnings("unchecked")
    private static EmbeddingResult<?> invoke(
            Object resource, Object input, Map<String, Object> parameters) {
        if (resource instanceof BaseEmbeddingModelConnection) {
            BaseEmbeddingModelConnection connection = (BaseEmbeddingModelConnection) resource;
            if (input instanceof String) return connection.embed((String) input, parameters);
            if (input instanceof DataContentBlock)
                return connection.embed((DataContentBlock) input, parameters);
            return connection.embed((List<? extends DataContentBlock>) input, parameters);
        }
        BaseEmbeddingModelSetup setup = (BaseEmbeddingModelSetup) resource;
        if (input instanceof String) return setup.embed((String) input, parameters);
        if (input instanceof DataContentBlock)
            return setup.embed((DataContentBlock) input, parameters);
        return setup.embed((List<? extends DataContentBlock>) input, parameters);
    }

    /**
     * Java provider used by the Python-agent E2E test, with a barrier proving overlapping calls.
     */
    public static class BarrierConnection extends BaseEmbeddingModelConnection {
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        public BarrierConnection(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public EmbeddingResult<List<float[]>> embed(
                List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
            if (((Number) parameters.get("dimension")).intValue() != 1) {
                throw new IllegalArgumentException("Per-call dimension override was lost");
            }
            if (Boolean.TRUE.equals(parameters.get("synchronize"))) {
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Embedding calls did not overlap", e);
                }
            }
            return new EmbeddingResult<>(
                    blocks.stream()
                            .map(
                                    block ->
                                            new float[] {
                                                block instanceof TextBlock
                                                        ? ((TextBlock) block).getText().length()
                                                        : 9
                                            })
                            .collect(Collectors.toList()),
                    Boolean.TRUE.equals(parameters.get("report_usage"))
                            ? new EmbeddingTokenUsage(7, 9)
                            : null);
        }
    }

    public static class BarrierSetup extends BaseEmbeddingModelSetup {
        public BarrierSetup(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("dimension", 2, "report_usage", true);
        }
    }
}
