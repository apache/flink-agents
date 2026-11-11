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

package org.apache.flink.agents.plan.resource.python;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.rerank.BaseRerankModel;
import org.apache.flink.agents.api.rerank.RerankResult;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Java access to a Python rerank model. */
public final class PythonRerankModel extends BaseRerankModel implements PythonResourceWrapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RERANK = "python_java_utils.call_rerank_model";

    private final PythonResourceAdapter adapter;
    private final PyObject resource;
    private boolean closed;

    public PythonRerankModel(
            PythonResourceAdapter adapter,
            PyObject resource,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.adapter = adapter;
        this.resource = resource;
    }

    @Override
    public void open() {
        adapter.callMethod(resource, "open", Map.of());
    }

    @Override
    public RerankResult rerank(
            DataContentBlock query,
            List<DataContentBlock> documents,
            @Nullable Integer topN,
            @Nullable Boolean returnDocuments,
            @Nullable String instruct,
            @Nullable Double timeout,
            Map<String, Object> parameters) {
        final String queryJson;
        final String documentsJson;
        try {
            queryJson = MAPPER.writeValueAsString(query);
            documentsJson = MAPPER.writeValueAsString(documents);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Rerank request must contain JSON-compatible values", e);
        }
        Object value =
                adapter.invoke(
                        RERANK,
                        resource,
                        queryJson,
                        documentsJson,
                        topN,
                        returnDocuments,
                        instruct,
                        timeout,
                        parameters);
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Invalid RerankModel invocation outcome from Python");
        }
        Map<?, ?> outcome = (Map<?, ?>) value;
        if ("INTERRUPTED".equals(outcome.get("status"))
                || "CANCELLED".equals(outcome.get("status"))) {
            Object message = outcome.get("message");
            if (!(message instanceof String)) {
                throw new IllegalStateException(
                        "Invalid RerankModel cancellation message from Python");
            }
            CancellationException cancellation = new CancellationException((String) message);
            if ("INTERRUPTED".equals(outcome.get("status"))) {
                cancellation.initCause(new InterruptedException(cancellation.getMessage()));
            }
            throw cancellation;
        }
        if (!"SUCCESS".equals(outcome.get("status"))) {
            throw new IllegalStateException("Invalid RerankModel invocation status from Python");
        }
        Object payload = outcome.get("result");
        if (!(payload instanceof String)) {
            throw new IllegalStateException("Invalid RerankModel invocation payload from Python");
        }
        try {
            return MAPPER.readValue((String) payload, RerankResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid RerankModel result from Python", e);
        }
    }

    @Override
    public Object getPythonResource() {
        return resource;
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
        if (closed) {
            return;
        }
        closed = true;
        try (resource) {
            adapter.callMethod(resource, "close", Map.of());
        }
    }
}
