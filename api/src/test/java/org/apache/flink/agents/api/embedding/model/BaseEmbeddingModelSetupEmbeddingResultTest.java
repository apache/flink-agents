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

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Contract tests for embedding conveniences and the provider batch implementation. */
class BaseEmbeddingModelSetupEmbeddingResultTest {
    private static final EmbeddingTokenUsage USAGE = new EmbeddingTokenUsage(7L, 9L);

    @Test
    void testSingleInputsDelegateToBatchAndPreserveUsageAndOverrides() throws Exception {
        TextConnection connection = new TextConnection();
        BaseEmbeddingModelSetup setup = openTextSetup(connection);
        for (EmbeddingResult<float[]> result :
                List.of(
                        setup.embed("hello", Map.of("dimension", 1)),
                        setup.embed(new TextBlock("hello"), Map.of("dimension", 1)),
                        connection.embed("hello", Map.of("dimension", 1)),
                        connection.embed(new TextBlock("hello"), Map.of("dimension", 1)))) {
            assertArrayEquals(new float[] {5}, result.getEmbeddings());
            assertSame(USAGE, result.getTokenUsage());
        }
        assertEquals(4, connection.calls);
        assertEquals(Map.of("dimension", 1), connection.lastParameters);
        assertEquals(Map.of("dimension", 2), setup.getParameters());
    }

    @Test
    void testBatchOrderAndEmptyBatchWithoutUsage() throws Exception {
        TextConnection connection = new TextConnection();
        connection.usage = null;
        BaseEmbeddingModelSetup setup = openTextSetup(connection);
        EmbeddingResult<List<float[]>> result =
                setup.embed(List.of(new TextBlock("first"), new TextBlock("next")));
        assertArrayEquals(new float[] {5}, result.getEmbeddings().get(0));
        assertArrayEquals(new float[] {4}, result.getEmbeddings().get(1));
        assertNull(result.getTokenUsage());
        assertEquals(List.of(), setup.embed(List.of()).getEmbeddings());
        assertNull(setup.embed("hello").getTokenUsage());
    }

    @Test
    void testTextProviderRejectsMediaBeforeServiceCalls() throws Exception {
        TextConnection connection = new TextConnection();
        BaseEmbeddingModelSetup setup = openTextSetup(connection);
        ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
        assertThrows(UnsupportedContentBlockException.class, () -> setup.embed(image));
        assertThrows(
                UnsupportedContentBlockException.class,
                () -> setup.embed(List.of(new TextBlock("caption"), image)));
        assertEquals(0, connection.calls);
    }

    @Test
    void testSingleInputRequiresExactlyOneVector() throws Exception {
        BaseEmbeddingModelConnection connection =
                mock(BaseEmbeddingModelConnection.class, CALLS_REAL_METHODS);
        doReturn(new EmbeddingResult<>(List.of(), null))
                .when(connection)
                .embed(anyList(), anyMap());
        assertThrows(IllegalStateException.class, () -> connection.embed("hello", Map.of()));
        BaseEmbeddingModelSetup setup = openTextSetup(connection);
        assertThrows(IllegalStateException.class, () -> setup.embed("hello"));
        doReturn(new EmbeddingResult<>(List.of(new float[] {1}, new float[] {2}), USAGE))
                .when(connection)
                .embed(anyList(), anyMap());
        assertThrows(
                IllegalStateException.class,
                () -> connection.embed(new TextBlock("hello"), Map.of()));
        assertThrows(IllegalStateException.class, () -> setup.embed(new TextBlock("hello")));
    }

    private static class TextConnection extends BaseEmbeddingModelConnection {
        private int calls;
        private Map<String, Object> lastParameters;
        private EmbeddingTokenUsage usage = USAGE;

        TextConnection() {
            super(new ResourceDescriptor("test", Map.of()), null);
        }

        @Override
        public EmbeddingResult<List<float[]>> embed(
                List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
            List<String> texts = EmbeddingModelUtils.requireTextInputs(blocks, "test");
            calls++;
            lastParameters = parameters;
            return new EmbeddingResult<>(
                    texts.stream()
                            .map(text -> new float[] {text.length()})
                            .collect(Collectors.toList()),
                    usage);
        }
    }

    private static BaseEmbeddingModelSetup openTextSetup(BaseEmbeddingModelConnection connection)
            throws Exception {
        ResourceContext context = mock(ResourceContext.class);
        when(context.getResource("text_connection", ResourceType.EMBEDDING_MODEL_CONNECTION))
                .thenReturn(connection);
        BaseEmbeddingModelSetup setup =
                new BaseEmbeddingModelSetup(
                        new ResourceDescriptor(
                                "test",
                                Map.of("connection", "text_connection", "model", "text-model")),
                        context) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("dimension", 2);
                    }
                };
        setup.open();
        return setup;
    }
}
