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
 * Unless required by applicable law or agreed in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;

/**
 * Python-based implementation of EmbeddingModelConnection that bridges Java and Python embedding
 * model functionality. This class wraps a Python embedding model connection object and provides
 * Java interface compatibility while delegating actual embed operations to the underlying Python
 * implementation.
 */
public class PythonEmbeddingModelConnection extends BaseEmbeddingModelConnection
        implements PythonResourceWrapper {

    private final PyObject embeddingModel;
    private final PythonResourceAdapter adapter;
    private boolean closed;

    /**
     * Creates a new PythonEmbeddingModelConnection.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param embeddingModel The Python embedding model object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonEmbeddingModelConnection(
            PythonResourceAdapter adapter,
            PyObject embeddingModel,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.embeddingModel = embeddingModel;
        this.adapter = adapter;
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedSingle(this, text, parameters);
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedBatch(this, texts, parameters);
    }

    @Override
    public EmbeddingResult<float[]> embedWithUsage(String text, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedSingleWithUsage(this, text, parameters);
    }

    @Override
    public EmbeddingResult<List<float[]>> embedWithUsage(
            List<String> texts, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedBatchWithUsage(this, texts, parameters);
    }

    @Override
    public EmbeddingResult<List<float[]>> embedBlocksWithUsage(
            List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedBatchWithUsage(this, blocks, parameters);
    }

    @Override
    public Object getPythonResource() {
        return embeddingModel;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public void close() throws Exception {
        if (closed || embeddingModel == null) {
            return;
        }
        closed = true;
        try (embeddingModel) {
            adapter.callMethod(embeddingModel, "close", Map.of());
        }
    }
}
