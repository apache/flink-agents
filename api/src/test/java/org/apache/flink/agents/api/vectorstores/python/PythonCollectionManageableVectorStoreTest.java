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

package org.apache.flink.agents.api.vectorstores.python;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.object.PyObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PythonCollectionManageableVectorStoreTest {
    @Mock private PythonResourceAdapter mockAdapter;

    @Mock private PyObject mockVectorStore;

    @Mock private ResourceDescriptor mockDescriptor;

    @Mock private BiFunction<String, ResourceType, Resource> mockGetResource;

    @Mock private PyObject mockPythonDocument;

    private PythonCollectionManageableVectorStore vectorStore;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        vectorStore =
                new PythonCollectionManageableVectorStore(
                        mockAdapter, mockVectorStore, mockDescriptor, mockGetResource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testConstructor() {
        assertThat(vectorStore).isNotNull();
        assertThat(vectorStore.getPythonResource()).isEqualTo(mockVectorStore);
    }

    @Test
    void testGetPythonResourceWithNullVectorStore() {
        PythonCollectionManageableVectorStore storeWithNull =
                new PythonCollectionManageableVectorStore(
                        mockAdapter, null, mockDescriptor, mockGetResource);

        Object result = storeWithNull.getPythonResource();

        assertThat(result).isNull();
    }

    @Test
    void testCreateCollectionIfNotExistsWithMetadata() throws Exception {
        String collectionName = "test_collection";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("metadata", metadata);

        vectorStore.createCollectionIfNotExists(collectionName, kwargs);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("create_collection_if_not_exists"),
                        argThat(
                                args -> {
                                    assertThat(args).containsKey("name");
                                    assertThat(args).containsKey("metadata");
                                    assertThat(args.get("name")).isEqualTo(collectionName);
                                    assertThat(args.get("metadata")).isEqualTo(metadata);
                                    return true;
                                }));
    }

    @Test
    void testCreateCollectionIfNotExistsWithEmptyKwargs() throws Exception {
        String collectionName = "test_collection";

        vectorStore.createCollectionIfNotExists(collectionName, new HashMap<>());

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("create_collection_if_not_exists"),
                        argThat(
                                args -> {
                                    assertThat(args).containsKey("name");
                                    assertThat(args).doesNotContainKey("metadata");
                                    assertThat(args.get("name")).isEqualTo(collectionName);
                                    return true;
                                }));
    }

    @Test
    void testCreateCollectionIfNotExistsWithNullKwargs() throws Exception {
        String collectionName = "test_collection";

        vectorStore.createCollectionIfNotExists(collectionName, null);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("create_collection_if_not_exists"),
                        argThat(
                                args -> {
                                    assertThat(args).containsKey("name");
                                    assertThat(args).doesNotContainKey("metadata");
                                    return true;
                                }));
    }

    @Test
    void testDeleteCollection() throws Exception {
        String collectionName = "collection_to_delete";

        vectorStore.deleteCollection(collectionName);

        verify(mockVectorStore).invokeMethod("delete_collection", collectionName);
    }

    @Test
    void testAddDocuments() throws Exception {
        List<Document> documents =
                Arrays.asList(
                        new Document("content1", Map.of("key", "value1"), "doc1"),
                        new Document("content2", Map.of("key", "value2"), "doc2"));
        String collection = "test_collection";
        Map<String, Object> extraArgs = Map.of("batch_size", 10);

        List<String> expectedIds = Arrays.asList("doc1", "doc2");

        when(mockAdapter.toPythonDocuments(documents)).thenReturn(new Object());
        when(mockAdapter.callMethod(eq(mockVectorStore), eq("add"), any(Map.class)))
                .thenReturn(expectedIds);

        List<String> result = vectorStore.add(documents, collection, extraArgs);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly("doc1", "doc2");

        verify(mockAdapter).toPythonDocuments(documents);
        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("add"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("documents");
                                    assertThat(kwargs).containsKey("collection_name");
                                    assertThat(kwargs).containsKey("batch_size");
                                    return true;
                                }));
    }

    @Test
    void testUpdateDocuments() throws Exception {
        List<Document> documents =
                Arrays.asList(
                        new Document("c1", Map.of("k", "v1"), "doc1"),
                        new Document("c2", Map.of("k", "v2"), "doc2"));
        String collection = "test_collection";
        Map<String, Object> extraArgs = Map.of("batch_size", 5);

        when(mockAdapter.toPythonDocuments(documents)).thenReturn(new Object());

        vectorStore.update(documents, collection, extraArgs);

        verify(mockAdapter).toPythonDocuments(documents);
        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("update"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("documents");
                                    assertThat(kwargs).containsKey("collection_name");
                                    assertThat(kwargs).containsKey("batch_size");
                                    return true;
                                }));
    }

    @Test
    void testGetDocuments() throws Exception {
        List<String> ids = Arrays.asList("doc1", "doc2");
        String collection = "test_collection";
        Map<String, Object> filters = Map.of("user_id", "u1");
        Integer limit = 50;
        Map<String, Object> extraArgs = new HashMap<>();

        List<Document> expectedDocuments =
                Arrays.asList(
                        new Document("content1", Map.of(), "doc1"),
                        new Document("content2", Map.of(), "doc2"));

        when(mockAdapter.callMethod(eq(mockVectorStore), eq("get"), any(Map.class)))
                .thenReturn(Arrays.asList(mockPythonDocument, mockPythonDocument));
        when(mockAdapter.fromPythonDocuments(any())).thenReturn(expectedDocuments);

        List<Document> result = vectorStore.get(ids, collection, filters, limit, extraArgs);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("get"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("ids");
                                    assertThat(kwargs).containsKey("collection_name");
                                    assertThat(kwargs).containsKey("filters");
                                    assertThat(kwargs).containsKey("limit");
                                    assertThat(kwargs.get("filters")).isEqualTo(filters);
                                    assertThat(kwargs.get("limit")).isEqualTo(limit);
                                    return true;
                                }));
    }

    @Test
    void testDeleteDocuments() throws Exception {
        List<String> ids = Arrays.asList("doc1", "doc2");
        String collection = "test_collection";
        Map<String, Object> filters = Map.of("category", "stale");
        Map<String, Object> extraArgs = new HashMap<>();

        when(mockAdapter.callMethod(eq(mockVectorStore), eq("delete"), any(Map.class)))
                .thenReturn(null);

        vectorStore.delete(ids, collection, filters, extraArgs);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("delete"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("ids");
                                    assertThat(kwargs).containsKey("collection_name");
                                    assertThat(kwargs).containsKey("filters");
                                    assertThat(kwargs.get("filters")).isEqualTo(filters);
                                    return true;
                                }));
    }

    @Test
    void testInheritanceFromPythonVectorStore() {
        assertThat(vectorStore).isInstanceOf(PythonVectorStore.class);
    }

    @Test
    void testImplementsCollectionManageableVectorStore() {
        assertThat(vectorStore).isInstanceOf(CollectionManageableVectorStore.class);
    }

    @Test
    void testImplementsPythonResourceWrapper() {
        assertThat(vectorStore)
                .isInstanceOf(
                        org.apache.flink.agents.api.resource.python.PythonResourceWrapper.class);
    }
}
