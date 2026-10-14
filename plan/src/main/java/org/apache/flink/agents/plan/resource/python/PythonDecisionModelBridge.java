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
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.Map;

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

    DecisionResult decide(DecisionRequest request, @Nullable String model) {
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
        String resultJson =
                (String)
                        adapter.invoke(
                                "python_java_utils.call_decision_model",
                                resource,
                                requestJson,
                                model);
        try {
            return MAPPER.readValue(resultJson, DecisionResult.class);
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
