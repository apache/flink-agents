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

package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.ForTestMemoryMapState;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.memory.MemoryUpdateReplayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chat tool-round parks the request context in sensory memory. Restoring that {@link
 * MemoryUpdate} through {@link ActionStateSerde} and replaying it must still echo the original
 * context on the final chat response.
 */
class ChatModelActionContextRecoveryTest {

    private static final String TOOL_REQUEST_EVENT_CONTEXT = "_TOOL_REQUEST_EVENT_CONTEXT";

    @Test
    void restoredToolRequestMemoryKeepsTheOriginalRequestContext() throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("task", "summary");
        context.put("item_id", "42");
        List<String> tags = new ArrayList<>();
        tags.add("a");
        context.put("tags", tags);

        List<Event> sentEvents = new ArrayList<>();
        List<MemoryUpdate> updates = new ArrayList<>();
        MemoryObject sensory = newMemory(updates);
        RunnerContext ctx = context(sensory, sentEvents);
        BaseChatModelSetup chatModel = mock(BaseChatModelSetup.class);
        when(ctx.getResource(anyString(), eq(ResourceType.CHAT_MODEL))).thenReturn(chatModel);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        new ChatResult(
                                ChatMessage.assistant(
                                        List.of(
                                                new ToolCallBlock(
                                                        "call-1", "lookup", Map.of("x", 1))))))
                .thenReturn(new ChatResult(ChatMessage.assistant("done")));

        ChatRequestEvent request =
                new ChatRequestEvent(
                        "test-model", List.of(ChatMessage.user("hi")), null, null, context);
        ChatModelAction.processChatRequestOrToolResponse(request, ctx);

        ToolRequestEvent toolRequest = ToolRequestEvent.fromEvent(sentEvents.get(0));
        assertThat(toolRequest.getUserContext()).isNull();
        Object liveParked = sensory.get(TOOL_REQUEST_EVENT_CONTEXT).getValue();

        ActionState state = new ActionState(request);
        for (MemoryUpdate update : updates) {
            state.addSensoryMemoryUpdate(update);
        }
        state.addEvent(toolRequest);
        ActionState restored = ActionStateSerde.deserialize(ActionStateSerde.serialize(state));

        MemoryUpdate parked =
                restored.getSensoryMemoryUpdates().stream()
                        .filter(update -> TOOL_REQUEST_EVENT_CONTEXT.equals(update.getPath()))
                        .reduce((first, second) -> second)
                        .orElseThrow();
        assertThat(parked.getValue()).isEqualTo(liveParked);
        assertThat(parked.getValue()).isNotSameAs(liveParked);

        MemoryObject recovered = newMemory(new ArrayList<>());
        MemoryUpdateReplayer.replay(recovered, restored.getSensoryMemoryUpdates());
        when(ctx.getSensoryMemory()).thenReturn(recovered);
        sentEvents.clear();

        ToolRequestEvent restoredTool =
                ToolRequestEvent.fromEvent(restored.getOutputEvents().get(0));
        assertThat(restoredTool.getId()).isEqualTo(toolRequest.getId());
        ChatModelAction.processChatRequestOrToolResponse(
                new ToolResponseEvent(
                        restoredTool.getId(),
                        Map.of("call-1", ToolResponse.text("ok")),
                        Map.of("call-1", true),
                        Map.of()),
                ctx);

        ChatResponseEvent response = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(response.getRequestId()).isEqualTo(request.getId());
        assertThat(response.getResponse().getText()).isEqualTo("done");
        assertThat(response.getUserContext()).isEqualTo(context);
        assertThat(response.getUserContext()).isNotSameAs(context);
    }

    private static MemoryObject newMemory(List<MemoryUpdate> updates) throws Exception {
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SENSORY,
                new CachedMemoryStore(new ForTestMemoryMapState<>()),
                MemoryObjectImpl.ROOT_KEY,
                updates);
    }

    private static RunnerContext context(MemoryObject sensory, List<Event> sentEvents)
            throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.MAX_RETRIES, 0);
        config.set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, 0);
        config.set(AgentExecutionOptions.CHAT_ASYNC, false);
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getSensoryMemory()).thenReturn(sensory);
        when(ctx.<Object>durableExecute(any()))
                .thenAnswer(
                        invocation -> invocation.<DurableCallable<Object>>getArgument(0).call());
        doAnswer(invocation -> sentEvents.add(invocation.getArgument(0)))
                .when(ctx)
                .sendEvent(any());
        return ctx;
    }
}
