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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * One logical chat invocation, spanning every model/tool turn up to the terminal response.
 * Resources and memory belong to the caller; only event routing belongs to the invocation.
 *
 * <p>Each invocation belongs to a {@link ChatCallOwner}, which may issue multiple chat calls during
 * the same action execution. This object holds one call's routing environment and terminal
 * response; the owner holds the caller's key and call counter.
 */
public final class ChatInvocation {
    private final String id;
    private final ChatCallOwner owner;
    private final UUID requestId;
    private final AgentPlan plan;
    private final RunnerContextImpl.MemoryContext memory;
    private final RunnerContextImpl.SubagentScope subagentScope;
    private final CompletableFuture<ChatResponseEvent> response = new CompletableFuture<>();
    private final boolean replayed;

    ChatInvocation(
            String id,
            ChatCallOwner owner,
            UUID requestId,
            AgentPlan plan,
            RunnerContextImpl.MemoryContext memory,
            RunnerContextImpl.SubagentScope subagentScope,
            boolean replayed) {
        this.id = id;
        this.owner = owner;
        this.requestId = requestId;
        this.plan = plan;
        this.memory = memory;
        this.subagentScope = subagentScope;
        this.replayed = replayed;
    }

    public String getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public RunnerContextImpl.MemoryContext getMemory() {
        return memory;
    }

    public RunnerContextImpl.SubagentScope getSubagentScope() {
        return subagentScope;
    }

    ChatCallOwner getOwner() {
        return owner;
    }

    boolean isReplayed() {
        return replayed;
    }

    public boolean isDone() {
        return response.isDone();
    }

    /** Waits for the terminal response without exposing the mutable completion future. */
    public ChatResponseEvent awaitResponse() throws InterruptedException, ExecutionException {
        return response.get();
    }

    public void complete(ChatResponseEvent event) {
        if (!requestId.equals(event.getRequestId())) {
            throw new IllegalStateException("Chat response does not match its request");
        }
        if (!response.complete(event)) {
            throw new IllegalStateException("Duplicate terminal chat response: " + id);
        }
    }

    public Action actionFor(Event event) {
        String name;
        if (ChatRequestEvent.EVENT_TYPE.equals(event.getType())
                || ToolResponseEvent.EVENT_TYPE.equals(event.getType())) {
            name = "chat_model_action";
        } else if (ToolRequestEvent.EVENT_TYPE.equals(event.getType())) {
            name = "tool_call_action";
        } else {
            throw new IllegalStateException("Unexpected chat call event: " + event.getType());
        }
        return Objects.requireNonNull(plan.getActions().get(name), "Missing built-in " + name);
    }
}
