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
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.tools.Tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a {@link QualitySignal} sees for one model round: the conversation the candidate received
 * (the original request plus every completed round, tool results included), the candidate's
 * response, the tools the candidate had, and where the request stands in the escalation tiers.
 */
public final class QualityContext {
    private final UUID requestId;
    private final String router;
    private final String candidate;
    private final int tierIndex;
    private final List<String> tiers;
    private final List<ChatMessage> messages;
    private final Map<String, Object> promptArgs;
    private final ChatResult response;
    private final List<Tool> tools;

    public QualityContext(
            UUID requestId,
            String router,
            String candidate,
            int tierIndex,
            List<String> tiers,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            ChatResult response,
            List<Tool> tools) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.router = Objects.requireNonNull(router, "router");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.tierIndex = tierIndex;
        this.tiers = Collections.unmodifiableList(List.copyOf(tiers));
        this.messages = Collections.unmodifiableList(List.copyOf(messages));
        this.promptArgs =
                promptArgs == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(promptArgs);
        this.response = Objects.requireNonNull(response, "response");
        this.tools = tools == null ? Collections.emptyList() : Collections.unmodifiableList(tools);
    }

    /** The initial request id of the routed request. */
    public UUID getRequestId() {
        return requestId;
    }

    /** The router the request was sent to. */
    public String getRouter() {
        return router;
    }

    /** The candidate that produced {@link #getResponse()}. */
    public String getCandidate() {
        return candidate;
    }

    /** Position of {@link #getCandidate()} in {@link #getTiers()}, cheapest first. */
    public int getTierIndex() {
        return tierIndex;
    }

    /** The declared escalation tiers, cheapest first. */
    public List<String> getTiers() {
        return tiers;
    }

    /** The messages the candidate received for this round. */
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /** The request's prompt arguments. */
    public Map<String, Object> getPromptArgs() {
        return promptArgs;
    }

    /** The candidate's response for this round: text, tool calls, or both. */
    public ChatResult getResponse() {
        return response;
    }

    /** The tools bound to the candidate, in declaration order; empty when it has none. */
    public List<Tool> getTools() {
        return tools;
    }

    /** The text of the last user message, or an empty string when there is none. */
    public String lastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == org.apache.flink.agents.api.chat.messages.MessageRole.USER) {
                String text = message.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }
}
