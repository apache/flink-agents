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
package org.apache.flink.agents.integrations.embeddingmodels.bedrock;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Embedding model setup for Bedrock Titan Text Embeddings.
 *
 * <p>Parameters:
 * <ul>
 *   <li><b>connection</b> (required): name of the BedrockEmbeddingModelConnection resource</li>
 *   <li><b>model</b> (optional): model ID (default: amazon.titan-embed-text-v2:0)</li>
 *   <li><b>dimensions</b> (optional): embedding dimensions (256, 512, or 1024)</li>
 * </ul>
 */
public class BedrockEmbeddingModelSetup extends BaseEmbeddingModelSetup {

    private final Integer dimensions;

    public BedrockEmbeddingModelSetup(
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.dimensions = descriptor.getArgument("dimensions");
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        if (model != null) params.put("model", model);
        if (dimensions != null) params.put("dimensions", dimensions);
        return params;
    }
}
