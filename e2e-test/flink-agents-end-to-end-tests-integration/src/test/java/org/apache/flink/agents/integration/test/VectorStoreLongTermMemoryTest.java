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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.LongTermMemoryOptions;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.memory.compaction.CompactionConfig;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup;
import org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.runtime.memory.VectorStoreLongTermMemory;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Test for {@link VectorStoreLongTermMemory}
 *
 * <p>We use {@link ElasticsearchVectorStore} as the backend of Long-Term Memory, so need setup
 * Elasticsearch server to run this test. Look <a
 * href="https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-basic">Start
 * a single-node cluster in Docker</a> for details.
 *
 * <p>For {@link ElasticsearchVectorStore} doesn't support security check yet, when start the
 * container, should add "-e xpack.security.enabled=false" option.
 */
@Disabled("Should setup Elasticsearch server.")
public class VectorStoreLongTermMemoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(VectorStoreLongTermMemoryTest.class);

    private static final String NAME = "chat-history";
    private final VectorStoreLongTermMemory ltm;
    private MemorySet memorySet;
    private List<ChatMessage> messages;

    public static Resource getResource(String name, ResourceType type) {
        if (type == ResourceType.CHAT_MODEL_CONNECTION) {
            return new OllamaChatModelConnection(
                    ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                            .addInitialArgument("endpoint", "http://localhost:11434")
                            .addInitialArgument("requestTimeout", 240)
                            .build(),
                    VectorStoreLongTermMemoryTest::getResource);
        } else if (type == ResourceType.CHAT_MODEL) {
            return new OllamaChatModelSetup(
                    ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                            .addInitialArgument("connection", "ollama-connection")
                            .addInitialArgument("model", "qwen3:8b")
                            .build(),
                    VectorStoreLongTermMemoryTest::getResource);
        } else if (type == ResourceType.EMBEDDING_MODEL_CONNECTION) {
            return new OllamaEmbeddingModelConnection(
                    ResourceDescriptor.Builder.newBuilder(
                                    OllamaEmbeddingModelConnection.class.getName())
                            .addInitialArgument("host", "http://localhost:11434")
                            .addInitialArgument("timeout", 120)
                            .build(),
                    VectorStoreLongTermMemoryTest::getResource);
        } else if (type == ResourceType.EMBEDDING_MODEL) {
            return new OllamaEmbeddingModelSetup(
                    ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelSetup.class.getName())
                            .addInitialArgument("connection", "embed-connection")
                            .addInitialArgument("model", "nomic-embed-text")
                            .build(),
                    VectorStoreLongTermMemoryTest::getResource);
        } else {
            return new ElasticsearchVectorStore(
                    ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                            .addInitialArgument("embedding_model", "embed-setup")
                            .addInitialArgument("host", "localhost:9200")
                            .addInitialArgument("dims", 768)
                            .build(),
                    VectorStoreLongTermMemoryTest::getResource);
        }
    }

    public VectorStoreLongTermMemoryTest() throws Exception {
        RunnerContext ctx = Mockito.mock(RunnerContext.class);

        AgentConfiguration config = new AgentConfiguration();
        config.set(LongTermMemoryOptions.ASYNC_COMPACTION, false);
        Mockito.when(ctx.getConfig()).thenReturn(config);

        Mockito.when(ctx.getResource("ollama-connection", ResourceType.CHAT_MODEL_CONNECTION))
                .thenReturn(getResource("ollama-connection", ResourceType.CHAT_MODEL_CONNECTION));

        Mockito.when(ctx.getResource("ollama-setup", ResourceType.CHAT_MODEL))
                .thenReturn(getResource("ollama-setup", ResourceType.CHAT_MODEL));

        Mockito.when(ctx.getResource("embed-connection", ResourceType.EMBEDDING_MODEL_CONNECTION))
                .thenReturn(
                        getResource("embed-connection", ResourceType.EMBEDDING_MODEL_CONNECTION));

        Mockito.when(ctx.getResource("embed-setup", ResourceType.EMBEDDING_MODEL))
                .thenReturn(getResource("embed-setup", ResourceType.EMBEDDING_MODEL));

        Mockito.when(ctx.getResource("vector-store", ResourceType.VECTOR_STORE))
                .thenReturn(getResource("vector-store", ResourceType.VECTOR_STORE));

        ltm = new VectorStoreLongTermMemory(ctx, "vector-store", "job-0001", "0001");
    }

    @BeforeEach
    public void prepare(TestInfo info) throws Exception {
        messages = new ArrayList<>();
        if (info.getTags().contains("skipBeforeEach")) {
            return;
        }
        memorySet =
                ltm.getOrCreateMemorySet(
                        NAME,
                        ChatMessage.class,
                        100,
                        new CompactionConfig("ollama-setup", null, 1));
        for (int i = 0; i < 10; i++) {
            messages.add(
                    new ChatMessage(
                            MessageRole.USER, String.format("This is the no.%s message", i)));
        }
        memorySet.add(messages, null, null);
    }

    @AfterEach
    public void cleanUp(TestInfo info) throws Exception {
        if (info.getTags().contains("skipAfterEach")) {
            return;
        }
        ltm.deleteMemorySet(NAME);
    }

    @Test
    public void testGetMemorySet() throws Exception {
        MemorySet retrieved = ltm.getMemorySet(memorySet.getName());

        Assertions.assertEquals(memorySet, retrieved);
    }

    @Test
    public void testAddAndGet() throws Exception {
        List<MemorySetItem> items = memorySet.get(null);
        List<ChatMessage> retrieved =
                items.stream().map(x -> (ChatMessage) x.getValue()).collect(Collectors.toList());
        Assertions.assertEquals(messages, retrieved);
    }

    @Test
    public void testSearch() throws Exception {
        List<MemorySetItem> items = memorySet.search("The no.5 message", 1, Collections.emptyMap());

        List<ChatMessage> retrieved =
                items.stream().map(x -> (ChatMessage) x.getValue()).collect(Collectors.toList());

        Assertions.assertEquals(1, retrieved.size());
        Assertions.assertEquals(messages.get(5), retrieved.get(0));
    }

    @Test
    @Tag("skipBeforeEach")
    public void testCompact() throws Exception {
        memorySet =
                ltm.getOrCreateMemorySet(
                        NAME, ChatMessage.class, 8, new CompactionConfig("ollama-setup", null, 2));
        messages.add(ChatMessage.user("What is flink?"));
        messages.add(
                ChatMessage.assistant(
                        "Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams. Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale."));
        messages.add(ChatMessage.user("What is flink agents?"));
        messages.add(
                ChatMessage.assistant(
                        "Apache Flink Agents is a brand-new sub-project from the Apache Flink community, providing an open-source framework for building event-driven streaming agents."));
        messages.add(ChatMessage.user("What's the whether tomorrow in london?"));
        messages.add(
                ChatMessage.assistant(
                        "",
                        Collections.singletonList(
                                Map.of(
                                        "id",
                                        "186780f8-c79d-4159-83e3-f65859835b14",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "get_weather",
                                                "arguments",
                                                Map.of(
                                                        "position",
                                                        "london",
                                                        "time",
                                                        "tomorrow"))))));
        messages.add(ChatMessage.tool("snow"));
        messages.add(ChatMessage.assistant("Tomorrow weather for london is snow."));
        memorySet.add(messages, null, null);

        List<MemorySetItem> items = memorySet.get(null);
        List<ChatMessage> retrieved =
                items.stream().map(x -> (ChatMessage) x.getValue()).collect(Collectors.toList());

        Assertions.assertEquals(2, items.size());
        Assertions.assertTrue(items.get(0).isCompacted());
        Assertions.assertTrue(items.get(1).isCompacted());
        Assertions.assertInstanceOf(
                MemorySetItem.DateTimeRange.class, items.get(0).getCreatedTime());
        Assertions.assertInstanceOf(
                MemorySetItem.DateTimeRange.class, items.get(1).getCreatedTime());
        Assertions.assertEquals(2, memorySet.size());
    }

    @Test
    @Tag("skipBeforeEach")
    @Tag("skipAfterEach")
    public void testUsingLtmInAction() throws Exception {
        ElasticsearchVectorStore es =
                new ElasticsearchVectorStore(
                        ResourceDescriptor.Builder.newBuilder(
                                        ElasticsearchVectorStore.class.getName())
                                .addInitialArgument("embedding_model", "embed-setup")
                                .addInitialArgument("host", "localhost:9200")
                                .addInitialArgument("dims", 768)
                                .build(),
                        VectorStoreLongTermMemoryTest::getResource);
        try {
            // Set up the Flink streaming environment and the Agents execution environment.
            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.setParallelism(1);
            AgentsExecutionEnvironment agentsEnv =
                    AgentsExecutionEnvironment.getExecutionEnvironment(env);
            agentsEnv.getConfig().set(AgentConfigOptions.JOB_IDENTIFIER, "ltm_test_job");
            agentsEnv
                    .getConfig()
                    .set(
                            LongTermMemoryOptions.BACKEND,
                            LongTermMemoryOptions.LongTermMemoryBackend.EXTERNAL_VECTOR_STORE);
            agentsEnv
                    .getConfig()
                    .set(LongTermMemoryOptions.EXTERNAL_VECTOR_STORE_NAME, "vectorStore");
            agentsEnv.getConfig().set(LongTermMemoryOptions.ASYNC_COMPACTION, true);

            DataStream<String> inputStream =
                    env.fromSource(
                            FileSource.forRecordStreamFormat(
                                            new TextLineInputFormat(),
                                            new Path(
                                                    Objects.requireNonNull(
                                                                    this.getClass()
                                                                            .getClassLoader()
                                                                            .getResource(
                                                                                    "input_data.txt"))
                                                            .getPath()))
                                    .build(),
                            WatermarkStrategy.noWatermarks(),
                            "ltm-test-agent");
            DataStream<VectorStoreLongTermMemoryAgent.ProductReview> reviewDataStream =
                    inputStream.map(
                            x ->
                                    VectorStoreLongTermMemoryAgent.mapper.readValue(
                                            x, VectorStoreLongTermMemoryAgent.ProductReview.class));

            // Use the ReviewAnalysisAgent to analyze each product review.
            DataStream<Object> outputStream =
                    agentsEnv
                            .fromDataStream(
                                    reviewDataStream,
                                    VectorStoreLongTermMemoryAgent.ProductReview::getId)
                            .apply(new VectorStoreLongTermMemoryAgent())
                            .toDataStream();

            // Print the analysis results to stdout.
            outputStream.print();

            // Execute the Flink pipeline.
            agentsEnv.execute();

            // check async compaction
            LOG.debug(es.get(null, "ltm_test_job-2-test-ltm", Collections.emptyMap()).toString());
            Assertions.assertEquals(1, es.size("ltm_test_job-2-test-ltm"));
        } finally {
            es.deleteCollection("ltm_test_job-1-test-ltm");
            es.deleteCollection("ltm_test_job-2-test-ltm");
            es.deleteCollection("ltm_test_job-3-test-ltm");
        }
    }
}
