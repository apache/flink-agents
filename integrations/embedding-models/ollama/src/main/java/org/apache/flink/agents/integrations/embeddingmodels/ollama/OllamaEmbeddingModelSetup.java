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

package org.apache.flink.agents.integrations.embeddingmodels.ollama;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** An embedding model setup for Ollama powered by the ollama4j client. */
public class OllamaEmbeddingModelSetup extends BaseEmbeddingModelSetup {

    public OllamaEmbeddingModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();

        // Add the model name if specified
        if (model != null) {
            parameters.put("model", model);
        }

        return parameters;
    }

    @Override
    public OllamaEmbeddingModelConnection getConnection() {
        return (OllamaEmbeddingModelConnection) super.getConnection();
    }
}
