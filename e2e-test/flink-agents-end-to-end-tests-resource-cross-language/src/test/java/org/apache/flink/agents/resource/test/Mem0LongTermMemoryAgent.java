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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.annotation.VectorStore;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.api.java.functions.KeySelector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * E2E test agent that mirrors the Python {@code long_term_memory_test.py}: streams {@link
 * ItemData}, asynchronously appends each fact to a Mem0-backed long-term memory under a per-name
 * key, and emits a per-record {@link OutputEvent} with the timestamps and (when applicable) the
 * full retrieved item set.
 *
 * <p>All resources are declared as native Java implementations (Ollama chat / embedding,
 * Elasticsearch vector store). Python's mem0 adapter consumes them through the cross-language
 * bridge: {@code ctx.get_resource(name, type)} on the Python side returns a Java*Impl wrapper that
 * delegates back into Java via Pemja.
 *
 * <p>The test driving this agent must (1) pull the Ollama models and (2) provide ES connection env
 * vars ({@code ES_HOST}, {@code ES_INDEX}, {@code ES_DIMS}, {@code ES_VECTOR_FIELD}, optional
 * {@code ES_USERNAME}/{@code ES_PASSWORD}); see {@link Mem0LongTermMemoryTest}.
 */
public class Mem0LongTermMemoryAgent extends Agent {

    public static final String CHAT_MODEL = "qwen3.6-plus";
    public static final String OLLAMA_EMBEDDING_MODEL = "nomic-embed-text";
    public static final String MEMORY_SET_NAME = "test_ltm";

    /** Mirrors the Python e2e: dashscope-hosted OpenAI-compatible endpoint, env-overridable. */
    private static final String DEFAULT_BASE_URL = "https://coding.dashscope.aliyuncs.com/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Per-name fact emitted by the test source. */
    public static class ItemData {
        public String name;
        public String fact;

        public ItemData() {}

        public ItemData(String name, String fact) {
            this.name = name;
            this.fact = fact;
        }
    }

    /** KeySelector — partitions the stream by username. */
    public static class ItemDataKeySelector implements KeySelector<ItemData, String> {
        @Override
        public String getKey(ItemData value) {
            return value.name;
        }
    }

    /** Carrier event — passes the in-progress {@code Record} between the two actions. */
    public static class MyEvent extends Event {
        private Map<String, Object> value;

        public MyEvent() {}

        public MyEvent(Map<String, Object> value) {
            this.value = value;
        }

        public Map<String, Object> getValue() {
            return value;
        }

        public void setValue(Map<String, Object> value) {
            this.value = value;
        }
    }

    @ChatModelConnection
    public static ResourceDescriptor openaiConnection() {
        String baseUrl = System.getenv().getOrDefault("ACTION_BASE_URL", DEFAULT_BASE_URL);
        String apiKey = System.getenv("ACTION_API_KEY");
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                .addInitialArgument("api_key", apiKey)
                .addInitialArgument("api_base_url", baseUrl)
                .addInitialArgument("request_timeout", 300)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor openaiQwen3() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                .addInitialArgument("connection", "openaiConnection")
                .addInitialArgument("model", CHAT_MODEL)
                .addInitialArgument("extract_reasoning", true)
                .addInitialArgument("think", false)
                .build();
    }

    @EmbeddingModelConnection
    public static ResourceDescriptor ollamaEmbeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OLLAMA_CONNECTION)
                .addInitialArgument("host", "http://localhost:11434")
                .addInitialArgument("timeout", 240)
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor ollamaNomicEmbedText() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaEmbeddingConnection")
                .addInitialArgument("model", OLLAMA_EMBEDDING_MODEL)
                .build();
    }

    @VectorStore
    public static ResourceDescriptor esLtmStore() {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(
                                ResourceName.VectorStore.ELASTICSEARCH_VECTOR_STORE)
                        .addInitialArgument("embedding_model", "ollamaNomicEmbedText")
                        .addInitialArgument("host", System.getenv("ES_HOST"))
                        .addInitialArgument(
                                "collection",
                                UUID.randomUUID().toString().substring(0, 8) + "-context");
        String username = System.getenv("ES_USERNAME");
        String password = System.getenv("ES_PASSWORD");
        if (username != null && password != null) {
            builder.addInitialArgument("username", username)
                    .addInitialArgument("password", password);
        }
        return builder.build();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void addItems(InputEvent event, RunnerContext ctx) throws Exception {
        ItemData input = (ItemData) event.getInput();
        BaseLongTermMemory ltm = ctx.getLongTermMemory();

        String timestampBeforeAdd = Instant.now().toString();
        MemorySet memorySet = ltm.getMemorySet(MEMORY_SET_NAME);
        ctx.durableExecuteAsync(
                new DurableCallable<Void>() {
                    @Override
                    public String getId() {
                        return "mem0-add-" + input.name;
                    }

                    @Override
                    public Class<Void> getResultClass() {
                        return Void.class;
                    }

                    @Override
                    public Void call() throws Exception {
                        memorySet.add(List.of(input.fact), null);
                        return null;
                    }
                });
        String timestampAfterAdd = Instant.now().toString();

        MemoryObject countObj = ctx.getShortTermMemory().get("count");
        int count = countObj == null ? 1 : ((Number) countObj.getValue()).intValue();
        ctx.getShortTermMemory().set("count", count + 1);

        Map<String, Object> record = new HashMap<>();
        record.put("name", input.name);
        record.put("count", count);
        record.put("timestamp_before_add", timestampBeforeAdd);
        record.put("timestamp_after_add", timestampAfterAdd);
        ctx.sendEvent(new MyEvent(record));
    }

    @Action(listenEvents = {MyEvent.class})
    public static void retrieveItems(MyEvent event, RunnerContext ctx) throws Exception {
        Map<String, Object> record = event.getValue();
        record.put("timestamp_second_action", Instant.now().toString());

        MemorySet memorySet = ctx.getLongTermMemory().getMemorySet(MEMORY_SET_NAME);
        String name = (String) record.get("name");
        int count = ((Number) record.get("count")).intValue();
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<MemorySetItem> items =
                ctx.durableExecuteAsync(
                        new DurableCallable<List>() {
                            @Override
                            public String getId() {
                                return "mem0-get-" + name + "-" + count;
                            }

                            @Override
                            public Class<List> getResultClass() {
                                return List.class;
                            }

                            @Override
                            public List<MemorySetItem> call() throws Exception {
                                return memorySet.get(null, null, null);
                            }
                        });
        if (("alice".equals(name) || "bob".equals(name)) && count == 2) {
            // Serialise the items as a JSON string. Embedding the raw List<Map<String,Object>>
            // here trips up Flink's Kryo deep-copy when chained operators forward the record:
            //   CollectionSerializer.copy -> ArrayList.add(NPE)
            // Stringifying sidesteps the Kryo path entirely (the test parses it back).
            List<Map<String, Object>> serialised = new ArrayList<>();
            for (MemorySetItem item : items) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", item.getId());
                m.put("value", item.getValue());
                m.put(
                        "created_at",
                        item.getCreatedAt() == null ? null : item.getCreatedAt().toString());
                m.put(
                        "updated_at",
                        item.getUpdatedAt() == null ? null : item.getUpdatedAt().toString());
                serialised.add(m);
            }
            record.put("items_json", MAPPER.writeValueAsString(serialised));
        }
        ctx.sendEvent(new OutputEvent(record));
    }
}
