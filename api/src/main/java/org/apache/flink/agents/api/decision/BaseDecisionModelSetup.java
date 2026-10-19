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

package org.apache.flink.agents.api.decision;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * A decision model configured with a connection and a model name.
 *
 * <p>Call {@link #open()} before using the setup. Registered setups are opened by the framework.
 * Connections are referenced by resource name and resolved through the resource context. Closing a
 * setup does not close a shared connection. Each call supplies its own evidence, questions, and
 * provider request parameters.
 */
public class BaseDecisionModelSetup extends Resource {
    private final ResourceDescriptor descriptor;
    @Nullable private String model;
    @Nullable private BaseDecisionModelConnection connection;

    /**
     * Configure a decision model using the descriptor's {@code connection} and {@code model}. The
     * configuration is validated by {@link #open()}.
     *
     * @param descriptor configuration containing a connection resource name and a non-blank
     *     provider model name
     * @param resourceContext resource lookup context, required when opening the setup
     */
    public BaseDecisionModelSetup(
            ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
        super(Objects.requireNonNull(descriptor, "descriptor"), resourceContext);
        this.descriptor = descriptor;
    }

    @Override
    public void open() throws Exception {
        if (connection != null) {
            return;
        }
        String configuredModel = requireNonBlankString(descriptor.getArgument("model"), "model");
        String connectionName =
                requireNonBlankString(descriptor.getArgument("connection"), "connection");
        if (resourceContext == null) {
            throw new IllegalStateException("A decision model setup requires a ResourceContext.");
        }
        Resource resolved =
                resourceContext.getResource(connectionName, ResourceType.DECISION_MODEL_CONNECTION);
        if (!(resolved instanceof BaseDecisionModelConnection)) {
            throw new IllegalArgumentException(
                    "The referenced resource must be a decision model connection.");
        }
        model = configuredModel;
        connection = (BaseDecisionModelConnection) resolved;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.DECISION_MODEL;
    }

    /** Whether calls through this setup may run on managed asynchronous workers. */
    @Internal
    public boolean supportsAsyncExecution() {
        return requireConnection().supportsAsyncExecution();
    }

    /** Evaluate named independent questions, preserving typed answers and provider errors. */
    public DecisionResult decide(DecisionRequest request) {
        return decide(request, Map.of());
    }

    /**
     * Evaluate questions with provider request parameters for this call only.
     *
     * @param request evidence and named questions
     * @param requestParams provider SDK request parameters containing JSON-compatible values;
     *     parameters cannot override the model, evidence, or questions
     */
    public DecisionResult decide(DecisionRequest request, Map<String, Object> requestParams) {
        return requireConnection().decide(request, model, requestParams);
    }

    private BaseDecisionModelConnection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("Open the setup before using the decision model.");
        }
        return connection;
    }

    private static String requireNonBlankString(Object value, String field) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalArgumentException(
                    "Decision model setup " + field + " must be a non-blank string.");
        }
        return (String) value;
    }
}
