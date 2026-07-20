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
import org.apache.flink.agents.api.resource.ResourceContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The information a {@link RoutingStrategy} sees when deciding which model to use for a single chat
 * request.
 *
 * <p>Carries the request messages and prompt args, the configured {@link RoutingCandidate}s, and
 * the {@link ResourceContext} so a strategy can resolve auxiliary resources it needs (for example
 * an LLM-as-router strategy resolving its judge {@code CHAT_MODEL}, or a future semantic strategy
 * resolving an embedding model).
 */
public class RoutingContext {

    private final List<ChatMessage> messages;
    private final Map<String, Object> promptArgs;
    private final List<RoutingCandidate> candidates;
    private final ResourceContext resourceContext;

    public RoutingContext(
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            List<RoutingCandidate> candidates,
            ResourceContext resourceContext) {
        this.messages =
                messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
        this.promptArgs = promptArgs != null ? promptArgs : Collections.emptyMap();
        this.candidates =
                candidates != null
                        ? Collections.unmodifiableList(candidates)
                        : Collections.emptyList();
        this.resourceContext = resourceContext;
    }

    /** The full request message list (immutable). */
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /** Variables supplied to fill the prompt template, if any (never null). */
    public Map<String, Object> getPromptArgs() {
        return promptArgs;
    }

    /** The models this router may route to (immutable, never null). */
    public List<RoutingCandidate> getCandidates() {
        return candidates;
    }

    /** Context for resolving other resources (e.g. a judge model). May be null in tests. */
    public ResourceContext getResourceContext() {
        return resourceContext;
    }

    /**
     * The content of the first {@link MessageRole#USER} message, or an empty string if there is
     * none.
     *
     * <p>Strategies should key their decision on this rather than the full (evolving) message list,
     * so that the same model is chosen on every round of a multi-turn tool-calling conversation —
     * the built-in chat action re-invokes the router with the accumulated messages on each tool
     * response. See {@link ChatModelRouter} for the stickiness contract.
     */
    public String firstUserMessage() {
        for (ChatMessage message : messages) {
            if (message.getRole() == MessageRole.USER) {
                return message.getContent() != null ? message.getContent() : "";
            }
        }
        return "";
    }
}
