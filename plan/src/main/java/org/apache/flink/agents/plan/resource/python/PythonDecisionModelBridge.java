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
import org.apache.flink.agents.api.decision.DecisionJson;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Owns the Python handle and the JSON boundary shared by decision setups and connections. */
final class PythonDecisionModelBridge implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    final PythonResourceAdapter adapter;
    final PyObject resource;
    private boolean closed;

    PythonDecisionModelBridge(PythonResourceAdapter adapter, PyObject resource) {
        this.adapter = adapter;
        this.resource = resource;
    }

    void open() {
        adapter.callMethod(resource, "open", Map.of());
    }

    DecisionResult decide(
            DecisionRequest request, @Nullable String model, Map<String, Object> requestParams) {
        if (closed) {
            throw new IllegalStateException("DecisionModel is closed");
        }
        final String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Decision request must contain JSON-compatible values", e);
        }
        Object value =
                adapter.invoke(
                        "python_java_utils.call_decision_model",
                        resource,
                        requestJson,
                        model,
                        DecisionJson.copy(
                                Objects.requireNonNull(requestParams, "requestParams"),
                                "request_params"));
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Invalid DecisionModel invocation outcome from Python");
        }
        Map<?, ?> outcome = (Map<?, ?>) value;
        Object status = outcome.get("status");
        if (!("SUCCESS".equals(status)
                        || "CANCELLED".equals(status)
                        || "INTERRUPTED".equals(status))
                || outcome.size() != 2) {
            throw new IllegalStateException("Invalid DecisionModel invocation status from Python");
        }
        Object payload = outcome.get("SUCCESS".equals(status) ? "result" : "message");
        if (!(payload instanceof String)) {
            throw new IllegalStateException("Invalid DecisionModel invocation payload from Python");
        }
        // Pemja erases Python exception types. Preserve these control signals explicitly.
        if ("INTERRUPTED".equals(status)) {
            CancellationException cancellation = new CancellationException((String) payload);
            cancellation.initCause(new InterruptedException(cancellation.getMessage()));
            throw cancellation;
        }
        if ("CANCELLED".equals(status)) {
            throw new CancellationException((String) payload);
        }
        try {
            return MAPPER.readValue((String) payload, DecisionResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid DecisionModel result from Python", e);
        }
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
