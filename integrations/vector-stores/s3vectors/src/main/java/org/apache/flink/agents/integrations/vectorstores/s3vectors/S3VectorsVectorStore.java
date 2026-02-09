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
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.*;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Amazon S3 Vectors vector store for flink-agents.
 */
public class S3VectorsVectorStore extends BaseVectorStore {

    private final S3VectorsClient client;
    private final String vectorBucket;
    private final String vectorIndex;

    public S3VectorsVectorStore(
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.vectorBucket = descriptor.getArgument("vector_bucket");
        this.vectorIndex = descriptor.getArgument("vector_index");
        String regionStr = descriptor.getArgument("region");
        this.client = S3VectorsClient.builder()
                .region(Region.of(regionStr != null ? regionStr : "us-east-1"))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /** Batch-embeds all documents in a single call, then delegates to addEmbedding. */
    @Override
    public List<String> add(List<Document> documents, @Nullable String collection,
                            Map<String, Object> extraArgs) throws IOException {
        BaseEmbeddingModelSetup emb = (BaseEmbeddingModelSetup)
                this.getResource.apply(this.embeddingModel, ResourceType.EMBEDDING_MODEL);
        List<String> texts = new ArrayList<>();
        List<Integer> needsEmbedding = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).getEmbedding() == null) {
                texts.add(documents.get(i).getContent());
                needsEmbedding.add(i);
            }
        }
        if (!texts.isEmpty()) {
            List<float[]> embeddings = emb.embed(texts);
            for (int j = 0; j < needsEmbedding.size(); j++) {
                documents.get(needsEmbedding.get(j)).setEmbedding(embeddings.get(j));
            }
        }
        return this.addEmbedding(documents, collection, extraArgs);
    }

    @Override
    public Map<String, Object> getStoreKwargs() {
        Map<String, Object> m = new HashMap<>();
        m.put("vector_bucket", vectorBucket);
        m.put("vector_index", vectorIndex);
        return m;
    }

    @Override
    public long size(@Nullable String collection) { return -1; }

    @Override
    public List<Document> get(@Nullable List<String> ids, @Nullable String collection,
                              Map<String, Object> extraArgs) throws IOException {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String idx = collection != null ? collection : vectorIndex;

        GetVectorsResponse response = client.getVectors(GetVectorsRequest.builder()
                .vectorBucketName(vectorBucket)
                .indexName(idx)
                .keys(ids)
                .returnMetadata(true)
                .build());

        List<Document> docs = new ArrayList<>();
        for (GetOutputVector v : response.vectors()) {
            docs.add(toDocument(v.key(), v.metadata()));
        }
        return docs;
    }

    @Override
    public void delete(@Nullable List<String> ids, @Nullable String collection,
                       Map<String, Object> extraArgs) throws IOException {
        if (ids == null || ids.isEmpty()) return;
        String idx = collection != null ? collection : vectorIndex;
        client.deleteVectors(DeleteVectorsRequest.builder()
                .vectorBucketName(vectorBucket).indexName(idx).keys(ids).build());
    }

    @Override
    protected List<Document> queryEmbedding(float[] embedding, int limit,
                                            @Nullable String collection, Map<String, Object> args) {
        try {
            String idx = collection != null ? collection : vectorIndex;
            int topK = (int) args.getOrDefault("top_k", Math.max(1, limit));

            List<Float> queryVector = new ArrayList<>(embedding.length);
            for (float v : embedding) queryVector.add(v);

            QueryVectorsResponse response = client.queryVectors(QueryVectorsRequest.builder()
                    .vectorBucketName(vectorBucket)
                    .indexName(idx)
                    .queryVector(VectorData.fromFloat32(queryVector))
                    .topK(topK)
                    .returnMetadata(true)
                    .build());

            List<Document> docs = new ArrayList<>();
            for (QueryOutputVector v : response.vectors()) {
                docs.add(toDocument(v.key(), v.metadata()));
            }
            return docs;
        } catch (Exception e) {
            throw new RuntimeException("S3 Vectors query failed.", e);
        }
    }

    private static final int MAX_PUT_VECTORS_BATCH = 500;

    @Override
    protected List<String> addEmbedding(List<Document> documents, @Nullable String collection,
                                        Map<String, Object> extraArgs) throws IOException {
        String idx = collection != null ? collection : vectorIndex;
        List<String> allKeys = new ArrayList<>();

        // Build all vectors first
        List<PutInputVector> allVectors = new ArrayList<>();
        for (Document doc : documents) {
            String key = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            allKeys.add(key);

            List<Float> embeddingList = new ArrayList<>();
            if (doc.getEmbedding() != null) {
                for (float v : doc.getEmbedding()) embeddingList.add(v);
            }

            Map<String, software.amazon.awssdk.core.document.Document> metaMap = new LinkedHashMap<>();
            metaMap.put("source_text",
                    software.amazon.awssdk.core.document.Document.fromString(doc.getContent()));
            if (doc.getMetadata() != null) {
                doc.getMetadata().forEach((k, v) -> metaMap.put(k,
                        software.amazon.awssdk.core.document.Document.fromString(String.valueOf(v))));
            }

            allVectors.add(PutInputVector.builder()
                    .key(key)
                    .data(VectorData.fromFloat32(embeddingList))
                    .metadata(software.amazon.awssdk.core.document.Document.fromMap(metaMap))
                    .build());
        }

        // Chunk into batches of 500 (S3 Vectors API limit)
        for (int i = 0; i < allVectors.size(); i += MAX_PUT_VECTORS_BATCH) {
            List<PutInputVector> batch = allVectors.subList(i,
                    Math.min(i + MAX_PUT_VECTORS_BATCH, allVectors.size()));
            client.putVectors(PutVectorsRequest.builder()
                    .vectorBucketName(vectorBucket).indexName(idx).vectors(batch).build());
        }
        return allKeys;
    }

    private Document toDocument(String key,
                                software.amazon.awssdk.core.document.Document metadata) {
        Map<String, Object> metaMap = new HashMap<>();
        String content = "";
        if (metadata != null && metadata.isMap()) {
            metadata.asMap().forEach((k, v) -> {
                if (v.isString()) metaMap.put(k, v.asString());
            });
            content = metaMap.getOrDefault("source_text", "").toString();
        }
        return new Document(content, metaMap, key);
    }
}
