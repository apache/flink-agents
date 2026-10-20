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
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.MemoryScope;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;
import org.apache.flink.agents.runtime.utils.DeterministicIdAllocator;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The built-in chat graph: one request in, exactly one response out. Its routing table, its result
 * contract, the caller's environment it runs in, and the entry point that mints one call's
 * identity.
 *
 * <p>The response is not matched against a request id: the delegate's id is not replay-stable, so
 * identity comes from the invocation the response was routed through.
 */
public final class ChatSubgraphDefinition implements SubgraphDefinition<ChatResponseEvent> {

    private final AgentPlan plan;

    ChatSubgraphDefinition(AgentPlan plan) {
        this.plan = plan;
    }

    /**
     * Discriminates chat calls among the targets of the caller's id allocation, and names the
     * durable slot holding their terminal response. One word for both, so an id can never be
     * assigned under a target other than the one its result is recorded in.
     */
    @Override
    public String kind() {
        return "builtin-chat";
    }

    /**
     * Builds the call answering one chat request for the action executing on {@code context}. The
     * request itself is the call's payload, handed to {@link
     * org.apache.flink.agents.runtime.subgraph.SubgraphExecution} separately so that a replay —
     * which never emits — never has to be given one. The id comes from the executing action's id
     * allocation, so a replay mints the same id and looks up the same durable slot.
     */
    public static SubgraphInvocation<ChatResponseEvent> invocationFor(RunnerContextImpl context) {
        ChatSubgraphDefinition definition = new ChatSubgraphDefinition(context.currentPlan());
        DeterministicIdAllocator ids = context.idAllocator(definition.kind());
        String id = ids.nextSessionId();
        return definition.newInvocation(context, id, context.getRecordKey());
    }

    @Override
    public Class<ChatResponseEvent> resultClass() {
        return ChatResponseEvent.class;
    }

    /**
     * The caller's request, kept under its own id so the response names the request the caller
     * holds. A copy rather than the caller's object because the emit path offloads the delegate's
     * attachments in place.
     */
    @Override
    public Event inputEvent(Object input) {
        ChatRequestEvent request = (ChatRequestEvent) input;
        ChatRequestEvent delegate = new ChatRequestEvent(request.getId(), request.getAttributes());
        delegate.getAttachments().putAll(request.getAttachments());
        return delegate;
    }

    /** A chat graph runs in its caller's environment and reads its caller's memory. */
    @Override
    public SubgraphInvocation<ChatResponseEvent> newInvocation(
            RunnerContextImpl context, String id, @Nullable Object key) {
        RunnerContextImpl.MemoryContext memory = context.getMemoryContext();
        return new SubgraphInvocation<>(
                id,
                key,
                this,
                new MemoryScope(memory.getSensoryMemStore(), memory.getShortTermMemStore()),
                context.getResourceScope());
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
        return ChatResponseEvent.fromEvent(outputs.get(0));
    }
}
