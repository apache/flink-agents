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
package org.apache.flink.agents.integrations.chatmodels.ollama;

import io.github.ollama4j.tools.Tools;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OllamaChatModelConnection}'s tool-schema conversion — no network access.
 */
class OllamaChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static OllamaChatModelConnection connection() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        return new OllamaChatModelConnection(desc, NOOP);
    }

    /** Minimal tool carrying only metadata; never invoked in these tests. */
    private static final class SchemaOnlyTool extends Tool {
        SchemaOnlyTool(String inputSchema) {
            super(new ToolMetadata("add", "Add two numbers.", inputSchema));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            throw new UnsupportedOperationException("not invoked in this test");
        }
    }

    @Test
    @DisplayName("A schema without a 'required' key converts with every property optional")
    void testSchemaWithoutRequiredKey() {
        // SchemaUtils only emits "required" when at least one parameter is required, so an
        // all-optional @Tool produces exactly this shape (#1014).
        String schema =
                "{\"type\":\"object\",\"properties\":{"
                        + "\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}";

        List<Tools.Tool> converted =
                connection().convertToOllamaTools(List.of(new SchemaOnlyTool(schema)));

        assertThat(converted).hasSize(1);
        Tools.Tool tool = converted.get(0);
        assertThat(tool.getToolSpec().getParameters().getProperties())
                .containsOnlyKeys("a", "b")
                .allSatisfy((name, property) -> assertThat(property.isRequired()).isFalse());
    }

    @Test
    @DisplayName("A schema with a 'required' key still marks the listed parameters required")
    void testSchemaWithRequiredKey() {
        String schema =
                "{\"type\":\"object\",\"properties\":{"
                        + "\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"a\"]}";

        List<Tools.Tool> converted =
                connection().convertToOllamaTools(List.of(new SchemaOnlyTool(schema)));

        assertThat(converted).hasSize(1);
        Tools.Tool tool = converted.get(0);
        assertThat(tool.getToolSpec().getParameters().getProperties().get("a").isRequired())
                .isTrue();
        assertThat(tool.getToolSpec().getParameters().getProperties().get("b").isRequired())
                .isFalse();
    }
}
