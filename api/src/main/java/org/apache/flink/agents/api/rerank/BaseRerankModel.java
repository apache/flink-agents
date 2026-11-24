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

package org.apache.flink.agents.api.rerank;

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A model that synchronously ranks documents against one query. */
public abstract class BaseRerankModel extends Resource {
    protected BaseRerankModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.RERANK_MODEL;
    }

    /** Rank documents using the configured model parameters and request timeout. */
    public RerankResult rerank(DataContentBlock query, List<? extends DataContentBlock> documents) {
        return rerank(query, documents, Map.of());
    }

    /** Rank documents with per-call model parameters and the configured request timeout. */
    public RerankResult rerank(
            DataContentBlock query,
            List<? extends DataContentBlock> documents,
            @Nullable Map<String, Object> modelParameters) {
        return rerank(query, documents, modelParameters, null);
    }

    /**
     * Rank documents against the query.
     *
     * <p>Call model parameters override matching resource configuration. If an option is absent
     * from both, the provider default applies.
     *
     * @param query query to rank the documents against
     * @param documents candidates in request order, which determines the index in each hit
     * @param modelParameters model parameters for this call, such as {@code top_n}, {@code
     *     return_documents}, and {@code instruct}
     * @param timeout HTTP timeout for this request, or null to use the configured request timeout
     */
    public abstract RerankResult rerank(
            DataContentBlock query,
            List<? extends DataContentBlock> documents,
            @Nullable Map<String, Object> modelParameters,
            @Nullable Duration timeout);
}
