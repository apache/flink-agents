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

package org.apache.flink.agents.api.embedding.model;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** Test cases for embedding results returned by model setups. */
class BaseEmbeddingModelSetupEmbeddingResultTest {

    @Test
    void testEmbedWithUsageDelegatesProviderUsage() {
        BaseEmbeddingModelSetup setup =
                new BaseEmbeddingModelSetup(
                        new ResourceDescriptor(
                                "test", Map.of("connection", "connection", "model", "model")),
                        mock(ResourceContext.class)) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Collections.emptyMap();
                    }
                };
        setup.connection =
                new BaseEmbeddingModelConnection(
                        new ResourceDescriptor("connection", Collections.emptyMap()),
                        mock(ResourceContext.class)) {
                    @Override
                    public float[] embed(String text, Map<String, Object> parameters) {
                        return new float[] {0.1f, 0.2f};
                    }

                    @Override
                    public java.util.List<float[]> embed(
                            java.util.List<String> texts, Map<String, Object> parameters) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public EmbeddingResult<float[]> embedWithUsage(
                            String text, Map<String, Object> parameters) {
                        return new EmbeddingResult<>(
                                embed(text, parameters), new EmbeddingTokenUsage(7L, 9L));
                    }
                };

        EmbeddingResult<float[]> result = setup.embedWithUsage("hello");

        assertArrayEquals(new float[] {0.1f, 0.2f}, result.getEmbeddings());
        assertEquals(7L, result.getTokenUsage().getPromptTokens());
        assertEquals(9L, result.getTokenUsage().getTotalTokens());
    }

    @Test
    void testEmbedDelegatesToExistingConnectionMethod() {
        BaseEmbeddingModelSetup setup =
                new BaseEmbeddingModelSetup(
                        new ResourceDescriptor(
                                "test", Map.of("connection", "connection", "model", "model")),
                        mock(ResourceContext.class)) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Collections.emptyMap();
                    }
                };
        setup.connection =
                new BaseEmbeddingModelConnection(
                        new ResourceDescriptor("connection", Collections.emptyMap()),
                        mock(ResourceContext.class)) {
                    @Override
                    public float[] embed(String text, Map<String, Object> parameters) {
                        return new float[] {0.1f, 0.2f};
                    }

                    @Override
                    public java.util.List<float[]> embed(
                            java.util.List<String> texts, Map<String, Object> parameters) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public EmbeddingResult<float[]> embedWithUsage(
                            String text, Map<String, Object> parameters) {
                        return new EmbeddingResult<>(
                                new float[] {0.3f, 0.4f}, new EmbeddingTokenUsage(7L, 9L));
                    }
                };

        assertArrayEquals(new float[] {0.1f, 0.2f}, setup.embed("hello"));
    }
}
