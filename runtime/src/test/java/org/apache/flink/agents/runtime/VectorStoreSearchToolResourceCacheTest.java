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

package org.apache.flink.agents.runtime;

import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.VectorStoreSearchTool;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Resolves {@link VectorStoreSearchTool} through a real {@link ResourceCache}. */
class VectorStoreSearchToolResourceCacheTest {

    private static final String TOOL = "search_docs";
    private static final String STORE = "knowledge_base";

    /** A tool provider as the operator sees it: restored from its serialized form. */
    private static ResourceProvider restoredToolProvider() throws Exception {
        JavaSerializableResourceProvider registered =
                JavaSerializableResourceProvider.createResourceProvider(
                        TOOL,
                        ResourceType.TOOL,
                        VectorStoreSearchTool.builder()
                                .name(TOOL)
                                .description("Search the docs.")
                                .vectorStore(STORE)
                                .build());
        return new JavaSerializableResourceProvider(
                registered.getName(),
                registered.getType(),
                registered.getModule(),
                registered.getClazz(),
                registered.getSerializedResource());
    }

    private static ResourceProvider providerOf(BaseVectorStore store) throws Exception {
        ResourceProvider provider = mock(ResourceProvider.class);
        when(provider.provide(any())).thenReturn(store);
        return provider;
    }

    @Test
    void restoredToolResolvesItsStoreOnceAndSharesItAcrossCalls() throws Exception {
        BaseVectorStore store = mock(BaseVectorStore.class);
        CountDownLatch bothQuerying = new CountDownLatch(2);
        when(store.query(any()))
                .thenAnswer(
                        invocation -> {
                            bothQuerying.countDown();
                            assertThat(bothQuerying.await(10, TimeUnit.SECONDS)).isTrue();
                            return new VectorStoreQueryResult(List.of());
                        });
        ResourceProvider storeProvider = providerOf(store);
        ResourceCache cache =
                new ResourceCache(
                        Map.of(
                                ResourceType.VECTOR_STORE, Map.of(STORE, storeProvider),
                                ResourceType.TOOL, Map.of(TOOL, restoredToolProvider())));

        VectorStoreSearchTool tool =
                (VectorStoreSearchTool) cache.getResource(TOOL, ResourceType.TOOL);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ToolResponse> first =
                    workers.submit(() -> tool.call(new ToolParameters(Map.of("query", "a"))));
            Future<ToolResponse> second =
                    workers.submit(() -> tool.call(new ToolParameters(Map.of("query", "b"))));
            assertThat(first.get(10, TimeUnit.SECONDS).getText())
                    .isEqualTo("No relevant documents found.");
            assertThat(second.get(10, TimeUnit.SECONDS).getText())
                    .isEqualTo("No relevant documents found.");
        } finally {
            workers.shutdownNow();
        }

        verify(storeProvider, times(1)).provide(any());
        verify(store, times(1)).open();
        verify(store, times(2)).query(any());

        cache.close();
        verify(store, times(1)).close();
    }

    @Test
    void undeclaredVectorStoreFailsWhenTheToolIsResolved() throws Exception {
        ResourceCache cache =
                new ResourceCache(Map.of(ResourceType.TOOL, Map.of(TOOL, restoredToolProvider())));

        assertThatThrownBy(() -> cache.getResource(TOOL, ResourceType.TOOL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(STORE);
        // A tool that failed to open is not cached, so the next lookup fails the same way.
        assertThatThrownBy(() -> cache.getResource(TOOL, ResourceType.TOOL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(STORE);
    }
}
