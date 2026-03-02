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

package org.apache.flink.agents.integrations.vectorstores.s3vectors;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link S3VectorsVectorStore}.
 *
 * <p>Integration tests require an S3 Vectors bucket and index. Set S3V_BUCKET env var to run.
 */
public class S3VectorsVectorStoreTest {

    private static final BiFunction<String, ResourceType, Resource> NOOP = (a, b) -> null;

    @Test
    @DisplayName("Constructor creates store")
    void testConstructor() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(S3VectorsVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "emb")
                        .addInitialArgument("vector_bucket", "my-bucket")
                        .addInitialArgument("vector_index", "my-index")
                        .addInitialArgument("region", "us-east-1")
                        .build();
        S3VectorsVectorStore store = new S3VectorsVectorStore(desc, NOOP);
        assertThat(store).isInstanceOf(BaseVectorStore.class);
    }

    @Test
    @DisplayName("getStoreKwargs returns bucket and index")
    void testStoreKwargs() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(S3VectorsVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "emb")
                        .addInitialArgument("vector_bucket", "test-bucket")
                        .addInitialArgument("vector_index", "test-index")
                        .build();
        S3VectorsVectorStore store = new S3VectorsVectorStore(desc, NOOP);
        Map<String, Object> kwargs = store.getStoreKwargs();
        assertThat(kwargs).containsEntry("vector_bucket", "test-bucket");
        assertThat(kwargs).containsEntry("vector_index", "test-index");
    }

    // --- Integration tests (require real S3 Vectors bucket) ---

    private static BaseVectorStore store;

    private static Resource getResource(String name, ResourceType type) {
        BaseEmbeddingModelSetup emb = Mockito.mock(BaseEmbeddingModelSetup.class);
        Mockito.when(emb.embed("Test document one"))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f});
        Mockito.when(emb.embed("Test document two"))
                .thenReturn(new float[] {0.5f, 0.4f, 0.3f, 0.2f, 0.1f});
        Mockito.when(emb.embed(Mockito.anyList()))
                .thenAnswer(
                        inv -> {
                            List<String> texts = inv.getArgument(0);
                            List<float[]> result = new ArrayList<>();
                            for (String t : texts) {
                                result.add(emb.embed(t));
                            }
                            return result;
                        });
        return emb;
    }

    @BeforeAll
    static void initialize() {
        String bucket = System.getenv("S3V_BUCKET");
        if (bucket == null) return;
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(S3VectorsVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "emb")
                        .addInitialArgument("vector_bucket", bucket)
                        .addInitialArgument(
                                "vector_index",
                                System.getenv().getOrDefault("S3V_INDEX", "test-index"))
                        .addInitialArgument(
                                "region", System.getenv().getOrDefault("AWS_REGION", "us-east-1"))
                        .build();
        store = new S3VectorsVectorStore(desc, S3VectorsVectorStoreTest::getResource);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "S3V_BUCKET", matches = ".+")
    @DisplayName("Document add and get")
    void testDocumentAddAndGet() throws Exception {
        List<Document> docs = new ArrayList<>();
        docs.add(new Document("Test document one", Map.of("src", "test"), "s3v-doc1"));
        docs.add(new Document("Test document two", Map.of("src", "test"), "s3v-doc2"));
        store.add(docs, null, Collections.emptyMap());

        List<Document> retrieved =
                store.get(List.of("s3v-doc1", "s3v-doc2"), null, Collections.emptyMap());
        Assertions.assertEquals(2, retrieved.size());

        store.delete(List.of("s3v-doc1", "s3v-doc2"), null, Collections.emptyMap());
    }
}
