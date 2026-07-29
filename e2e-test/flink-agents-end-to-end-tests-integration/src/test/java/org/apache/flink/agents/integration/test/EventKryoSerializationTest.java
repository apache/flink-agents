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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.vectorstores.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies that the user-constructed Event classes which carry collection-typed payloads remain
 * Kryo-friendly when constructed with immutable collections (e.g. List.of(...)).
 *
 * <p>On JDK 17+, Flink's Kryo serializer cannot reflectively access internal fields of {@code
 * java.util.ImmutableCollections} subclasses without {@code --add-opens
 * java.base/java.util=ALL-UNNAMED}. The fix is to defensive-copy the input collection at event
 * construction time so the stored collection is always a plain {@link java.util.ArrayList}, which
 * Kryo handles natively.
 *
 * <p>Scope: this test only covers {@link ChatRequestEvent} and {@link
 * ContextRetrievalResponseEvent}, which are constructed directly by user code. {@code
 * ToolRequestEvent} and {@code ToolResponseEvent} are intentionally excluded because they are
 * constructed only by built-in framework actions, not by users, so defensive copies on those events
 * would only add overhead without protecting any real caller.
 *
 * <p>This test asserts the contract directly: the stored collection must be mutable, i.e. {@code
 * add()} must not throw {@link UnsupportedOperationException}, which is exactly what would happen
 * if the immutable input were stored by reference.
 */
public class EventKryoSerializationTest {

    @Test
    void chatRequestEventDefensiveCopiesImmutableList() {
        ChatRequestEvent event =
                new ChatRequestEvent(
                        "myModel", List.of(new ChatMessage(MessageRole.USER, "hello")));

        assertDoesNotThrow(
                () -> event.getMessages().add(new ChatMessage(MessageRole.USER, "world")),
                "ChatRequestEvent must defensive-copy its messages list");
    }

    @Test
    void contextRetrievalResponseEventDefensiveCopiesImmutableList() {
        UUID requestId = UUID.randomUUID();
        ContextRetrievalResponseEvent event =
                new ContextRetrievalResponseEvent(
                        requestId,
                        "what is flink agents?",
                        List.of(
                                new Document(
                                        "Apache Flink Agents is a streaming agent framework.",
                                        Map.of(),
                                        "doc-1")));

        assertDoesNotThrow(
                () -> event.getDocuments().add(new Document("extra", Map.of(), "doc-2")),
                "ContextRetrievalResponseEvent must defensive-copy its documents list");
    }
}
