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

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests for {@link BedrockEmbeddingModelConnection} and {@link BedrockEmbeddingModelSetup}. */
class BedrockEmbeddingModelTest {

    private static final BiFunction<String, ResourceType, Resource> NOOP = (a, b) -> null;

    private static ResourceDescriptor connDescriptor(String region) {
        ResourceDescriptor.Builder b = ResourceDescriptor.Builder
                .newBuilder(BedrockEmbeddingModelConnection.class.getName());
        if (region != null) b.addInitialArgument("region", region);
        return b.build();
    }

    @Test
    @DisplayName("Connection constructor creates client with defaults")
    void testConnectionDefaults() {
        BedrockEmbeddingModelConnection conn =
                new BedrockEmbeddingModelConnection(connDescriptor(null), NOOP);
        assertNotNull(conn);
        assertThat(conn).isInstanceOf(BaseEmbeddingModelConnection.class);
    }

    @Test
    @DisplayName("Connection constructor with explicit region and concurrency")
    void testConnectionExplicitParams() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(BedrockEmbeddingModelConnection.class.getName())
                .addInitialArgument("region", "eu-west-1")
                .addInitialArgument("embed_concurrency", 8)
                .build();
        BedrockEmbeddingModelConnection conn =
                new BedrockEmbeddingModelConnection(desc, NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Setup getParameters includes model and dimensions")
    void testSetupParameters() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(BedrockEmbeddingModelSetup.class.getName())
                .addInitialArgument("connection", "conn")
                .addInitialArgument("model", "amazon.titan-embed-text-v2:0")
                .addInitialArgument("dimensions", 1024)
                .build();
        BedrockEmbeddingModelSetup setup = new BedrockEmbeddingModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params).containsEntry("model", "amazon.titan-embed-text-v2:0");
        assertThat(params).containsEntry("dimensions", 1024);
        assertThat(setup).isInstanceOf(BaseEmbeddingModelSetup.class);
    }

    @Test
    @DisplayName("Setup getParameters omits null dimensions")
    void testSetupParametersNoDimensions() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(BedrockEmbeddingModelSetup.class.getName())
                .addInitialArgument("connection", "conn")
                .addInitialArgument("model", "amazon.titan-embed-text-v2:0")
                .build();
        BedrockEmbeddingModelSetup setup = new BedrockEmbeddingModelSetup(desc, NOOP);

        assertThat(setup.getParameters()).doesNotContainKey("dimensions");
    }
}
