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

package org.apache.flink.agents.api.chat.model.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only view a {@link RoutingStrategy} sees when deciding which model to route to.
 *
 * <p>v1 exposes the request id, the request messages, prompt args, and the router's candidates
 * (name + description). It intentionally does <b>not</b> expose a chat-invocation API, so a
 * strategy cannot make a hidden synchronous model call; observable LLM-as-router is a
 * framework-managed follow-up.
 */
public final class RoutingContext {

    private final UUID requestId;
    private final String router;
    private final List<ChatMessage> messages;
    private final Map<String, Object> promptArgs;
    private final List<RoutingCandidate> candidates;

    public RoutingContext(
            UUID requestId,
            String router,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            List<RoutingCandidate> candidates) {
        this.requestId = requestId;
        this.router = router;
        this.messages =
                messages == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(messages));
        this.promptArgs =
                promptArgs == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(promptArgs));
        this.candidates =
                candidates == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    /**
     * Id of the initial chat request being routed. Lets strategies correlate their own logs with
     * the framework's events, and enables deterministic per-request policies (e.g. hash-based A/B
     * splits).
     */
    public UUID getRequestId() {
        return requestId;
    }

    /** Name of the router resource handling this request. */
    public String getRouter() {
        return router;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public Map<String, Object> getPromptArgs() {
        return promptArgs;
    }

    public List<RoutingCandidate> getCandidates() {
        return candidates;
    }

    /**
     * Content of the first user message, or an empty string if there is none. Note that when the
     * request carries conversation history this is the <b>oldest</b> user turn; strategies that
     * should react to the current question want {@link #lastUserMessage()}.
     */
    public String firstUserMessage() {
        for (ChatMessage message : messages) {
            if (message.getRole() == MessageRole.USER) {
                return message.getContent() == null ? "" : message.getContent();
            }
        }
        return "";
    }

    /**
     * Content of the most recent user message, or an empty string if there is none. This is the
     * current question in a multi-turn conversation and the default input for rule/keyword
     * strategies.
     */
    public String lastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == MessageRole.USER) {
                return message.getContent() == null ? "" : message.getContent();
            }
        }
        return "";
    }
}
