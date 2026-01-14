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
import pemja.core.object.PyObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Python-based implementation of VectorStore with collection management capabilities that bridges
 * Java and Python vector store functionality. This class wraps a Python vector store object and
 * provides Java interface compatibility while delegating actual storage, retrieval, and collection
 * management operations to the underlying Python implementation.
 *
 * <p>Unlike {@link PythonVectorStore}, this implementation provides additional collection
 * management features, allowing for operations such as creating, listing, and deleting collections
 * within the vector store.
 */
public class PythonCollectionManageableVectorStore extends PythonVectorStore
        implements CollectionManageableVectorStore {
    /**
     * Creates a new PythonEmbeddingModelConnection.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param vectorStore The Python vector store object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonCollectionManageableVectorStore(
            PythonResourceAdapter adapter,
            PyObject vectorStore,
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(adapter, vectorStore, descriptor, getResource);
    }

    @Override
    public Collection getOrCreateCollection(String name, Map<String, Object> metadata)
            throws Exception {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("name", name);
        if (metadata != null && !metadata.isEmpty()) {
            kwargs.put("metadata", metadata);
        }

        Object result = this.adapter.callMethod(vectorStore, "get_or_create_collection", kwargs);
        return adapter.fromPythonCollection((PyObject) result);
    }

    @Override
    public Collection getCollection(String name) throws Exception {
        Object result = this.vectorStore.invokeMethod("get_collection", name);
        return adapter.fromPythonCollection((PyObject) result);
    }

    @Override
    public Collection deleteCollection(String name) throws Exception {
        Object result = this.vectorStore.invokeMethod("delete_collection", name);
        return adapter.fromPythonCollection((PyObject) result);
    }
}
