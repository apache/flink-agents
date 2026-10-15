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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.EventAttachmentUtils;
import org.apache.flink.agents.runtime.subgraph.SubgraphCallManager;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.util.Preconditions;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Mailbox-confined chat adapter over the operator's common subgraph registry.
 *
 * <p>Supplies request identity, caller memory/resource scope and the durable terminal-response
 * slot. Routing, completion accounting and active invocation lookup belong to the subgraph runtime.
 */
public final class ChatCallManager {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final SubgraphCallManager calls;

    public ChatCallManager() {
        this(new SubgraphCallManager());
    }

    public ChatCallManager(SubgraphCallManager calls) {
        this.calls = calls;
    }

    /** Starts/replays a call on the action thread. Python uses the same persisted event format. */
    public String prepareCall(RunnerContextImpl context, String requestJson) throws Exception {
        context.checkMailboxThread();
        ChatCallOwner owner = context.getChatContext().getOwner();
        Preconditions.checkState(owner != null, "Chat calls must be awaited inside an action");
        ChatRequestEvent supplied = (ChatRequestEvent) Event.fromJson(requestJson);
        String id = owner.nextCallId();
        Optional<Outcome<ChatResponseEvent>> cached =
                context.tryGetCachedResult("builtin-chat:" + id, ChatResponseEvent.class);
        // Replayed responses keep their original request id; use the same id to validate them.
        // This is chat request/response correlation, not the subgraph's ActionState identity.
        UUID requestId = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        RunnerContextImpl.MemoryContext memory = context.getMemoryContext();
        ChatInvocation call =
                new ChatInvocation(
                        id,
                        owner,
                        requestId,
                        context.currentPlan(),
                        new RunnerContextImpl.MemoryContext(
                                memory.getSensoryMemStore(), memory.getShortTermMemStore()),
                        context.getResourceScope(),
                        cached.isPresent());
        calls.register(call);
        if (cached.isPresent()) {
            call.complete(cached.get().getValue());
        } else {
            ChatRequestEvent request = new ChatRequestEvent(requestId, supplied.getAttributes());
            request.getAttachments().putAll(supplied.getAttachments());
            EventAttachmentUtils.storeEventAttachments(request, context);
            // Start a nested graph; the caller remains in flight while awaiting its result.
            context.addPendingEvent(new SubgraphEvent(id, request, true));
        }
        return id;
    }

    /**
     * Returns null while pending; persists only terminal ChatResponseEvents, never runtime errors.
     * The serialized response is also the Python bridge's wire format.
     */
    public String tryCompleteCall(RunnerContextImpl context, String id) throws Exception {
        context.checkMailboxThread();
        ChatInvocation call = get(id);
        if (!call.isDone()) {
            return null;
        }
        ChatResponseEvent response = call.awaitResponse();
        if (!call.isReplayed()) {
            context.recordDurableCompletion("builtin-chat:" + id, response, null);
        }
        String json = OBJECT_MAPPER.writeValueAsString(response);
        calls.remove(id);
        return json;
    }

    public ChatInvocation get(String id) {
        return (ChatInvocation) calls.get(id);
    }

    public void clear() {
        calls.clear();
    }

    /** Also releases handles abandoned by user code before it finishes processing a record. */
    public void finishRecord(Object key) {
        calls.finishRecord(key);
    }
}
