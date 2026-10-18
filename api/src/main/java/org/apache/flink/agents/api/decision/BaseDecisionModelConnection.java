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

import java.util.Map;

/** Connection to a decision service. The resource lifecycle owns its SDK client. */
public abstract class BaseDecisionModelConnection extends Resource {
    protected BaseDecisionModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.DECISION_MODEL_CONNECTION;
    }

    /** Whether decision calls may run on the runtime's managed asynchronous workers. */
    @Internal
    public boolean supportsAsyncExecution() {
        return true;
    }

    /** Evaluate named independent questions, preserving typed answers and provider errors. */
    public DecisionResult decide(DecisionRequest request, String model) {
        return decide(request, model, Map.of());
    }

    /**
     * Evaluate questions with provider request parameters for this call only.
     *
     * @param request evidence and named questions
     * @param model model identifier
     * @param requestParams provider SDK request parameters containing JSON-compatible values;
     *     parameters cannot override the model, evidence, or questions
     */
    public abstract DecisionResult decide(
            DecisionRequest request, String model, Map<String, Object> requestParams);
}
