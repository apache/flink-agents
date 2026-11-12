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

import java.util.List;
import java.util.Map;

/**
 * A model that ranks documents against one query.
 *
 * <p>The call is synchronous. The Java type is the caller-facing contract. A provider may be
 * implemented in Python and reached through the cross-language bridge.
 */
public abstract class BaseRerankModel extends Resource {
    protected BaseRerankModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.RERANK_MODEL;
    }

    /**
     * Rank documents against the query.
     *
     * <p>Unspecified ranking options use the resource configuration. If an option is also absent
     * from that configuration, the provider default applies.
     *
     * @param topN maximum hits, or null to leave this option unspecified
     * @param returnDocuments whether hits include the original document, or null to leave this
     *     option unspecified
     * @param instruct ranking instruction, or null to leave this option unspecified
     * @param timeout seconds for this request, or null for the provider timeout
     */
    public abstract RerankResult rerank(
            DataContentBlock query,
            List<DataContentBlock> documents,
            @Nullable Integer topN,
            @Nullable Boolean returnDocuments,
            @Nullable String instruct,
            @Nullable Double timeout,
            Map<String, Object> parameters);
}
