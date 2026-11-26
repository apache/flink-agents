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

package org.apache.flink.agents.api.embedding.model;

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.List;
import java.util.Map;

/**
 * Abstraction of embedding model connection.
 *
 * <p>Responsible for managing embedding model service connection configurations, such as Service
 * address, API key, Connection timeout, Model name, Authentication information, etc.
 *
 * <p>This class follows the parameter pattern where additional configuration options can be passed
 * through a Map&lt;String, Object&gt; parameters argument. Common parameters include:
 *
 * <ul>
 *   <li>model - The model name to use for embeddings
 *   <li>encoding_format - The format for encoding (e.g., "float", "base64")
 *   <li>timeout - Request timeout in milliseconds
 *   <li>batch_size - Maximum number of texts to process in a single request
 * </ul>
 */
public abstract class BaseEmbeddingModelConnection extends Resource {

    public BaseEmbeddingModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.EMBEDDING_MODEL_CONNECTION;
    }

    /** Generate one vector for text, with optional provider token usage. */
    public EmbeddingResult<float[]> embed(String text, Map<String, Object> parameters) {
        return embed(new TextBlock(text), parameters);
    }

    /** Generate one vector for a supported block by invoking the batch implementation. */
    public EmbeddingResult<float[]> embed(DataContentBlock block, Map<String, Object> parameters) {
        return EmbeddingModelUtils.singleResult(embed(List.of(block), parameters));
    }

    /**
     * Generate one vector per block in input order, with optional usage for the batch.
     *
     * <p>Providers must reject unsupported content before making service calls. Text-only providers
     * can use {@link EmbeddingModelUtils#requireTextInputs(List, String)}.
     */
    public abstract EmbeddingResult<List<float[]>> embed(
            List<? extends DataContentBlock> blocks, Map<String, Object> parameters);
}
