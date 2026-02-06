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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.memory.compaction.CompactionConfig;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup;
import org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore;
import org.junit.jupiter.api.Assertions;

import java.util.Collections;
import java.util.List;

public class VectorStoreLongTermMemoryAgent extends Agent {
    public static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.registerModule(new JavaTimeModule());
    }

    /** Data model representing a product review. */
    @JsonSerialize
    @JsonDeserialize
    public static class ProductReview {
        private final int id;
        private final String review;

        @JsonCreator
        public ProductReview(@JsonProperty("id") int id, @JsonProperty("review") String review) {
            this.id = id;
            this.review = review;
        }

        public int getId() {
            return id;
        }

        public String getReview() {
            return review;
        }

        @Override
        public String toString() {
            return String.format("ProductReview{id='%s', review='%s'}", id, review);
        }
    }

    /** Custom event type for internal agent communication. */
    public static class MyEvent extends Event {
        private final int recordId;

        public MyEvent(int recordId) {
            this.recordId = recordId;
        }

        public int getRecordId() {
            return recordId;
        }
    }

    private static final String CHAT_MODEL = "qwen3:8b";
    public static final String EMBED_MODEL = "nomic-embed-text";

    @ChatModelConnection
    public static ResourceDescriptor chatModelConnection() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 240)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor ollamaQwen3() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "chatModelConnection")
                .addInitialArgument("model", CHAT_MODEL)
                .build();
    }

    @EmbeddingModelConnection
    public static ResourceDescriptor embeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelConnection.class.getName())
                .addInitialArgument("host", "http://localhost:11434")
                .addInitialArgument("timeout", 120)
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelSetup.class.getName())
                .addInitialArgument("connection", "embeddingConnection")
                .addInitialArgument("model", EMBED_MODEL)
                .build();
    }

    @VectorStore
    public static ResourceDescriptor vectorStore() {
        return ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                .addInitialArgument("embedding_model", "embeddingModel")
                .addInitialArgument("host", "localhost:9200")
                .addInitialArgument("dims", 768)
                .build();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void addItems(InputEvent event, RunnerContext ctx) throws Exception {
        BaseLongTermMemory ltm = ctx.getLongTermMemory();
        MemorySet memorySet =
                ltm.getOrCreateMemorySet(
                        "test-ltm", String.class, 5, new CompactionConfig("ollamaQwen3", 1));
        ProductReview review = (ProductReview) event.getInput();
        memorySet.add(Collections.singletonList(review.getReview()), null, null);

        MemoryObject stm = ctx.getShortTermMemory();

        if (stm.isExist("count")) {
            int count = (int) stm.get("count").getValue();
            stm.set("count", count + 1);
        } else {
            stm.set("count", 1);
        }

        ctx.sendEvent(new MyEvent(review.id));
    }

    @Action(listenEvents = {MyEvent.class})
    public static void retrieveItems(MyEvent event, RunnerContext ctx) throws Exception {
        int count = (int) ctx.getShortTermMemory().get("count").getValue();

        BaseLongTermMemory ltm = ctx.getLongTermMemory();
        MemorySet memorySet = ltm.getMemorySet("test-ltm");

        Assertions.assertEquals(String.class, memorySet.getItemType());
        Assertions.assertEquals(count, memorySet.size());

        List<MemorySetItem> items = memorySet.get(null);
        ctx.sendEvent(new OutputEvent(mapper.writeValueAsString(items)));
    }
}
