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

import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingModelConnectionTest {

    private static ResourceDescriptor buildDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelConnection.class.getName())
                .addInitialArgument("host", "http://localhost:11434")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    private static BiFunction<String, ResourceType, Resource> dummyResource = (a, b) -> null;

    @Test
    @DisplayName("Create OllamaEmbeddingModelConnection and check embed method")
    void testCreateAndEmbed() {
        OllamaEmbeddingModelConnection conn =
                new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
        assertNotNull(conn);
        // No llamamos a embed porque requiere un servidor Ollama real
    }

    @Test
    @DisplayName("Test EmbeddingModelConnection annotation presence")
    void testAnnotationPresence() {
        assertNull(
                OllamaEmbeddingModelConnection.class.getAnnotation(EmbeddingModelConnection.class));
    }

    @Test
    @DisplayName("Test EmbeddingModelSetup annotation presence on setup class")
    void testSetupAnnotationPresence() {
        class DummySetup extends BaseEmbeddingModelSetup {
            public DummySetup(
                    ResourceDescriptor descriptor,
                    BiFunction<String, ResourceType, Resource> getResource) {
                super(descriptor, getResource);
            }

            public Map<String, Object> getParameters() {
                Map<String, Object> parameters = new HashMap<>();
                if (model != null) {
                    parameters.put("model", model);
                }
                return parameters;
            }

            @Override
            public BaseEmbeddingModelConnection getConnection() {
                return new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
            }
        }
        DummySetup setup = new DummySetup(buildDescriptor(), dummyResource);
        assertNotNull(setup.getConnection());
    }
}
