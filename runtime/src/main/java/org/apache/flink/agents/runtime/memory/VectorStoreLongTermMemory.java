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
package org.apache.flink.agents.runtime.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.LongTermMemoryOptions;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.memory.compaction.CompactionConfig;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore.Collection;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.ExecutorUtils;
import org.apache.flink.util.concurrent.ExecutorThreadFactory;

import javax.annotation.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.agents.runtime.memory.CompactionFunctions.summarize;

public class VectorStoreLongTermMemory implements InteranlBaseLongTermMemory {
    public static final ObjectMapper mapper = new ObjectMapper();
    public static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    private final RunnerContext ctx;
    private final boolean asyncCompaction;

    private final String jobId;
    private Map<String, AtomicBoolean> inCompaction;
    private String key;
    private transient ExecutorService lazyCompactExecutor;
    private Object vectorStore;

    public VectorStoreLongTermMemory(RunnerContext ctx, Object vectorStore, String jobId) {
        this(ctx, vectorStore, jobId, null);
    }

    @VisibleForTesting
    public VectorStoreLongTermMemory(
            RunnerContext ctx, Object vectorStore, String jobId, String key) {
        this.ctx = ctx;
        this.vectorStore = vectorStore;
        this.jobId = jobId;
        this.key = key;
        this.asyncCompaction = ctx.getConfig().get(LongTermMemoryOptions.ASYNC_COMPACTION);
        if (this.asyncCompaction) {
            inCompaction = new WeakHashMap<>();
        }
    }

    @Override
    public void switchContext(String key) {
        this.key = key;
    }

    private BaseVectorStore store() throws Exception {
        if (vectorStore instanceof String) {
            vectorStore = ctx.getResource((String) vectorStore, ResourceType.VECTOR_STORE);
        }
        return (BaseVectorStore) vectorStore;
    }

    @Override
    public MemorySet getOrCreateMemorySet(
            String name, Class<?> itemType, int capacity, CompactionConfig compactionConfig)
            throws Exception {
        MemorySet memorySet = new MemorySet(name, itemType, capacity, compactionConfig);
        ((CollectionManageableVectorStore) this.store())
                .getOrCreateCollection(
                        this.nameMangling(name),
                        Map.of("memory_set", mapper.writeValueAsString(memorySet)));
        memorySet.setLtm(this);
        return memorySet;
    }

    @Override
    public MemorySet getMemorySet(String name) throws Exception {
        Collection collection =
                ((CollectionManageableVectorStore) this.store())
                        .getCollection(this.nameMangling(name));
        MemorySet memorySet =
                mapper.readValue(
                        (String) collection.getMetadata().get("memory_set"), MemorySet.class);
        memorySet.setLtm(this);
        return memorySet;
    }

    @Override
    public boolean deleteMemorySet(String name) throws Exception {
        Collection collection =
                ((CollectionManageableVectorStore) this.store())
                        .deleteCollection(this.nameMangling(name));
        return collection != null;
    }

    @Override
    public long size(MemorySet memorySet) throws Exception {
        return this.store().size(this.nameMangling(memorySet.getName()));
    }

    @Override
    public List<String> add(
            MemorySet memorySet,
            List<?> memoryItems,
            @Nullable List<String> ids,
            @Nullable List<Map<String, Object>> metadatas)
            throws Exception {
        if (ids == null || ids.isEmpty()) {
            ids = new ArrayList<>();
            for (int i = 0; i < memoryItems.size(); i++) {
                ids.add(UUID.randomUUID().toString());
            }
        }

        String timestamp = LocalDateTime.now().format(formatter);
        Map<String, Object> metadata =
                Map.of(
                        "compacted",
                        false,
                        "created_time",
                        timestamp,
                        "last_accessed_time",
                        timestamp);

        List<Map<String, Object>> mergedMetadatas = new ArrayList<>();
        for (int i = 0; i < memoryItems.size(); i++) {
            mergedMetadatas.add(new HashMap<>(metadata));
        }

        if (metadatas != null && !metadatas.isEmpty()) {
            for (int i = 0; i < memoryItems.size(); i++) {
                mergedMetadatas.get(i).putAll(metadatas.get(i));
            }
        }

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < memoryItems.size(); i++) {
            documents.add(
                    new Document(
                            mapper.writeValueAsString(memoryItems.get(i)),
                            mergedMetadatas.get(i),
                            ids.get(i)));
        }

        List<String> itemIds =
                this.store()
                        .add(
                                documents,
                                this.nameMangling(memorySet.getName()),
                                Collections.emptyMap());

        if (memorySet.size() >= memorySet.getCapacity()) {
            if (this.asyncCompaction) {
                String name = this.nameMangling(memorySet.getName());
                AtomicBoolean isCompacting =
                        this.inCompaction.computeIfAbsent(name, k -> new AtomicBoolean(false));
                if (isCompacting.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(
                                    () -> {
                                        try {
                                            asyncCompact(memorySet, isCompacting);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    },
                                    this.workerExecutor())
                            .exceptionally(
                                    e -> {
                                        throw new RuntimeException(
                                                String.format(
                                                        "Compaction for %s failed",
                                                        this.nameMangling(memorySet.getName())),
                                                e);
                                    });
                }
            } else {
                this.compact(memorySet);
            }
        }

        return itemIds;
    }

    // TODO: get the entire set at once may cause OOM, should support batched get.
    @Override
    public List<MemorySetItem> get(MemorySet memorySet, @Nullable List<String> ids)
            throws Exception {
        List<Document> documents =
                this.store()
                        .get(ids, this.nameMangling(memorySet.getName()), Collections.emptyMap());
        return this.convertToItems(memorySet, documents);
    }

    @Override
    public void delete(MemorySet memorySet, @Nullable List<String> ids) throws Exception {
        this.store().delete(ids, this.nameMangling(memorySet.getName()), Collections.emptyMap());
    }

    @Override
    public List<MemorySetItem> search(
            MemorySet memorySet, String query, int limit, Map<String, Object> extraArgs)
            throws Exception {
        VectorStoreQuery vectorStoreQuery =
                new VectorStoreQuery(
                        query, limit, this.nameMangling(memorySet.getName()), extraArgs);
        VectorStoreQueryResult result = this.store().query(vectorStoreQuery);
        return this.convertToItems(memorySet, result.getDocuments());
    }

    private String nameMangling(String name) {
        return String.join("-", this.jobId, this.key, name);
    }

    private List<MemorySetItem> convertToItems(MemorySet memorySet, List<Document> documents)
            throws JsonProcessingException {
        List<MemorySetItem> items = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            boolean compacted = (boolean) metadata.remove("compacted");
            Object createdTime;
            if (compacted) {
                createdTime =
                        new MemorySetItem.DateTimeRange(
                                LocalDateTime.parse(
                                        (String) metadata.remove("created_time_start"), formatter),
                                LocalDateTime.parse(
                                        (String) metadata.remove("created_time_end"), formatter));
            } else {
                createdTime =
                        LocalDateTime.parse((String) metadata.remove("created_time"), formatter);
            }
            MemorySetItem item =
                    new MemorySetItem(
                            memorySet.getName(),
                            doc.getId(),
                            memorySet.getItemType() == String.class
                                    ? doc.getContent()
                                    : mapper.readValue(doc.getContent(), memorySet.getItemType()),
                            compacted,
                            createdTime,
                            LocalDateTime.parse(
                                    (String) metadata.remove("last_accessed_time"), formatter),
                            metadata);
            items.add(item);
        }
        return items;
    }

    private void compact(MemorySet memorySet) throws Exception {
        summarize(this, memorySet, ctx, null);
    }

    private void asyncCompact(MemorySet memorySet, AtomicBoolean inCompaction) throws Exception {
        summarize(this, memorySet, ctx, null);
        inCompaction.set(false);
    }

    private ExecutorService workerExecutor() {
        // TODO: shutdown executor when close.
        if (lazyCompactExecutor == null) {
            int nThreads = ctx.getConfig().get(LongTermMemoryOptions.THREAD_COUNT);
            lazyCompactExecutor =
                    Executors.newFixedThreadPool(
                            nThreads,
                            new ExecutorThreadFactory(
                                    Thread.currentThread().getName() + "-ltm-compact-worker"));
        }
        return lazyCompactExecutor;
    }

    @Override
    public void close() throws Exception {
        if (lazyCompactExecutor != null) {
            ExecutorUtils.gracefulShutdown(180, TimeUnit.SECONDS, lazyCompactExecutor);
            lazyCompactExecutor = null;
        }
    }
}
