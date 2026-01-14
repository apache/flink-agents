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

    @Mock private PyObject mockPythonCollection;

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
    void testGetOrCreateCollectionWithMetadata() throws Exception {
        String collectionName = "test_collection";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");

        CollectionManageableVectorStore.Collection expectedCollection =
                new CollectionManageableVectorStore.Collection(collectionName, metadata);

        when(mockAdapter.callMethod(
                        eq(mockVectorStore), eq("get_or_create_collection"), any(Map.class)))
                .thenReturn(mockPythonCollection);
        when(mockAdapter.fromPythonCollection(mockPythonCollection)).thenReturn(expectedCollection);

        CollectionManageableVectorStore.Collection result =
                vectorStore.getOrCreateCollection(collectionName, metadata);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(collectionName);
        assertThat(result.getMetadata()).isEqualTo(metadata);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("get_or_create_collection"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("name");
                                    assertThat(kwargs).containsKey("metadata");
                                    assertThat(kwargs.get("name")).isEqualTo(collectionName);
                                    assertThat(kwargs.get("metadata")).isEqualTo(metadata);
                                    return true;
                                }));
    }

    @Test
    void testGetOrCreateCollectionWithEmptyMetadata() throws Exception {
        String collectionName = "test_collection";
        Map<String, Object> metadata = new HashMap<>();

        CollectionManageableVectorStore.Collection expectedCollection =
                new CollectionManageableVectorStore.Collection(collectionName, metadata);

        when(mockAdapter.callMethod(
                        eq(mockVectorStore), eq("get_or_create_collection"), any(Map.class)))
                .thenReturn(mockPythonCollection);
        when(mockAdapter.fromPythonCollection(mockPythonCollection)).thenReturn(expectedCollection);

        CollectionManageableVectorStore.Collection result =
                vectorStore.getOrCreateCollection(collectionName, metadata);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(collectionName);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("get_or_create_collection"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("name");
                                    assertThat(kwargs).doesNotContainKey("metadata");
                                    return true;
                                }));
    }

    @Test
    void testGetOrCreateCollectionWithNullMetadata() throws Exception {
        String collectionName = "test_collection";

        CollectionManageableVectorStore.Collection expectedCollection =
                new CollectionManageableVectorStore.Collection(collectionName, null);

        when(mockAdapter.callMethod(
                        eq(mockVectorStore), eq("get_or_create_collection"), any(Map.class)))
                .thenReturn(mockPythonCollection);
        when(mockAdapter.fromPythonCollection(mockPythonCollection)).thenReturn(expectedCollection);

        CollectionManageableVectorStore.Collection result =
                vectorStore.getOrCreateCollection(collectionName, null);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(collectionName);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("get_or_create_collection"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("name");
                                    assertThat(kwargs).doesNotContainKey("metadata");
                                    return true;
                                }));
    }

    @Test
    void testGetCollection() throws Exception {
        String collectionName = "existing_collection";
        Map<String, Object> metadata = Map.of("type", "test");

        CollectionManageableVectorStore.Collection expectedCollection =
                new CollectionManageableVectorStore.Collection(collectionName, metadata);

        when(mockVectorStore.invokeMethod("get_collection", collectionName))
                .thenReturn(mockPythonCollection);
        when(mockAdapter.fromPythonCollection(mockPythonCollection)).thenReturn(expectedCollection);

        CollectionManageableVectorStore.Collection result =
                vectorStore.getCollection(collectionName);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(collectionName);
        assertThat(result.getMetadata()).isEqualTo(metadata);

        verify(mockVectorStore).invokeMethod("get_collection", collectionName);
        verify(mockAdapter).fromPythonCollection(mockPythonCollection);
    }

    @Test
    void testDeleteCollection() throws Exception {
        String collectionName = "collection_to_delete";
        Map<String, Object> metadata = Map.of("status", "deleted");

        CollectionManageableVectorStore.Collection expectedCollection =
                new CollectionManageableVectorStore.Collection(collectionName, metadata);

        when(mockVectorStore.invokeMethod("delete_collection", collectionName))
                .thenReturn(mockPythonCollection);
        when(mockAdapter.fromPythonCollection(mockPythonCollection)).thenReturn(expectedCollection);

        CollectionManageableVectorStore.Collection result =
                vectorStore.deleteCollection(collectionName);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(collectionName);

        verify(mockVectorStore).invokeMethod("delete_collection", collectionName);
        verify(mockAdapter).fromPythonCollection(mockPythonCollection);
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
                                    assertThat(kwargs).containsKey("collection");
                                    assertThat(kwargs).containsKey("batch_size");
                                    return true;
                                }));
    }

    @Test
    void testGetDocuments() throws Exception {
        List<String> ids = Arrays.asList("doc1", "doc2");
        String collection = "test_collection";
        Map<String, Object> extraArgs = new HashMap<>();

        List<Document> expectedDocuments =
                Arrays.asList(
                        new Document("content1", Map.of(), "doc1"),
                        new Document("content2", Map.of(), "doc2"));

        when(mockAdapter.callMethod(eq(mockVectorStore), eq("get"), any(Map.class)))
                .thenReturn(Arrays.asList(mockPythonCollection, mockPythonCollection));
        when(mockAdapter.fromPythonDocuments(any())).thenReturn(expectedDocuments);

        List<Document> result = vectorStore.get(ids, collection, extraArgs);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("get"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("ids");
                                    assertThat(kwargs).containsKey("collection");
                                    return true;
                                }));
    }

    @Test
    void testDeleteDocuments() throws Exception {
        List<String> ids = Arrays.asList("doc1", "doc2");
        String collection = "test_collection";
        Map<String, Object> extraArgs = new HashMap<>();

        when(mockAdapter.callMethod(eq(mockVectorStore), eq("delete"), any(Map.class)))
                .thenReturn(null);

        vectorStore.delete(ids, collection, extraArgs);

        verify(mockAdapter)
                .callMethod(
                        eq(mockVectorStore),
                        eq("delete"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("ids");
                                    assertThat(kwargs).containsKey("collection");
                                    return true;
                                }));
    }

    @Test
    void testSize() throws Exception {
        String collection = "test_collection";
        long expectedSize = 100L;

        when(mockVectorStore.invokeMethod("size", collection)).thenReturn(expectedSize);

        long result = vectorStore.size(collection);

        assertThat(result).isEqualTo(expectedSize);
        verify(mockVectorStore).invokeMethod("size", collection);
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
