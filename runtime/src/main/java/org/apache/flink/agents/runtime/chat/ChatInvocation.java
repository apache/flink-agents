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

import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Typed chat result adapter on the common subgraph lifecycle. The coordinator, not this adapter,
 * owns routing, action accounting and completion. The replay flag prevents recording an already
 * durable response a second time.
 */
public final class ChatInvocation extends SubgraphInvocation<ChatResponseEvent> {
    private final UUID requestId;
    private final boolean replayed;

    ChatInvocation(
            String id,
            ChatCallOwner owner,
            UUID requestId,
            AgentPlan plan,
            RunnerContextImpl.MemoryContext memory,
            RunnerContextImpl.ResourceScope resourceScope,
            boolean replayed) {
        super(
                id,
                owner.getKey(),
                new ChatSubgraphDefinition(plan, requestId),
                memory,
                resourceScope);
        this.requestId = requestId;
        this.replayed = replayed;
    }

    public UUID getRequestId() {
        return requestId;
    }

    boolean isReplayed() {
        return replayed;
    }

    public ChatResponseEvent awaitResponse() throws InterruptedException, ExecutionException {
        return awaitResult();
    }

    /** Installs an already recorded response without dispatching the graph. */
    public void complete(ChatResponseEvent event) {
        if (!requestId.equals(event.getRequestId())) {
            throw new IllegalStateException("Chat response does not match its request");
        }
        replay(event);
    }
}
