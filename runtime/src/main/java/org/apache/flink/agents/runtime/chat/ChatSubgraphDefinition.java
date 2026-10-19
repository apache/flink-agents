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
package org.apache.flink.agents.runtime.chat;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat's private routing table and exactly-one-response contract, independent of resource scope.
 */
final class ChatSubgraphDefinition implements SubgraphDefinition<ChatResponseEvent> {
    private final AgentPlan plan;
    private final UUID requestId;

    ChatSubgraphDefinition(AgentPlan plan, UUID requestId) {
        this.plan = plan;
        this.requestId = requestId;
    }

    @Override
    public List<Action> matchActions(Event event) {
        String name;
        if (ChatRequestEvent.EVENT_TYPE.equals(event.getType())
                || ToolResponseEvent.EVENT_TYPE.equals(event.getType())) {
            name = "chat_model_action";
        } else if (ToolRequestEvent.EVENT_TYPE.equals(event.getType())) {
            name = "tool_call_action";
        } else {
            // Observation events remain private and do not trigger the caller's listeners.
            return List.of();
        }
        return List.of(
                Objects.requireNonNull(plan.getActions().get(name), "Missing built-in " + name));
    }

    @Override
    public boolean isOutput(Event event) {
        return ChatResponseEvent.EVENT_TYPE.equals(event.getType());
    }

    @Override
    public ChatResponseEvent finish(List<Event> outputs) {
        if (outputs.size() != 1) {
            throw new IllegalStateException(
                    "Chat subgraph requires exactly one response, got " + outputs.size());
        }
        ChatResponseEvent response = ChatResponseEvent.fromEvent(outputs.get(0));
        if (!requestId.equals(response.getRequestId())) {
            throw new IllegalStateException("Chat response does not match its request");
        }
        return response;
    }
}
