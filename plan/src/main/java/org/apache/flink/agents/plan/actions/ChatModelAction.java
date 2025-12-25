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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.JavaFunction;

import java.util.*;

/** Built-in action for processing chat request and tool call result. */
public class ChatModelAction {
    private static final String TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT";
    private static final String TOOL_REQUEST_EVENT_CONTEXT = "_TOOL_REQUEST_EVENT_CONTEXT";
    private static final String INITIAL_REQUEST_ID = "initialRequestId";
    private static final String MODEL = "model";

    public static Action getChatModelAction() throws Exception {
        return new Action(
                "chat_model_action",
                new JavaFunction(
                        ChatModelAction.class,
                        "processChatRequestOrToolResponse",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(ChatRequestEvent.class.getName(), ToolResponseEvent.class.getName()));
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> updateToolCallContext(
            MemoryObject sensoryMem,
            UUID initialRequestId,
            List<ChatMessage> initialMessages,
            List<ChatMessage> addedMessages)
            throws Exception {

        Map<UUID, Object> toolCallContext;
        if (sensoryMem.isExist(TOOL_CALL_CONTEXT)) {
            toolCallContext = (Map<UUID, Object>) sensoryMem.get(TOOL_CALL_CONTEXT).getValue();
        } else {
            toolCallContext = new HashMap<>();
        }
        if (!toolCallContext.containsKey(initialRequestId)) {
            toolCallContext.put(initialRequestId, initialMessages);
        }
        List<ChatMessage> messageContext =
                new ArrayList<>((List<ChatMessage>) toolCallContext.get(initialRequestId));

        messageContext.addAll(addedMessages);
        toolCallContext.put(initialRequestId, messageContext);
        sensoryMem.set(TOOL_CALL_CONTEXT, toolCallContext);
        return messageContext;
    }

    @SuppressWarnings("unchecked")
    private static void clearToolCallContext(MemoryObject sensoryMem, UUID initialRequestId)
            throws Exception {
        if (sensoryMem.isExist(TOOL_CALL_CONTEXT)) {
            Map<UUID, Object> toolCallContext =
                    (Map<UUID, Object>) sensoryMem.get(TOOL_CALL_CONTEXT).getValue();
            if (toolCallContext.containsKey(initialRequestId)) {
                toolCallContext.remove(initialRequestId);
                sensoryMem.set(TOOL_CALL_CONTEXT, toolCallContext);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void saveToolRequestEventContext(
            MemoryObject sensoryMem, UUID toolRequestEventId, UUID initialRequestId, String model)
            throws Exception {
        Map<UUID, Object> toolRequestEventContext;
        if (sensoryMem.isExist(TOOL_REQUEST_EVENT_CONTEXT)) {
            toolRequestEventContext =
                    (Map<UUID, Object>) sensoryMem.get(TOOL_REQUEST_EVENT_CONTEXT).getValue();
        } else {
            toolRequestEventContext = new HashMap<>();
        }
        toolRequestEventContext.put(
                toolRequestEventId, Map.of(INITIAL_REQUEST_ID, initialRequestId, MODEL, model));
        sensoryMem.set(TOOL_REQUEST_EVENT_CONTEXT, toolRequestEventContext);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> removeToolRequestEventContext(
            MemoryObject sensoryMem, UUID requestId) throws Exception {
        Map<UUID, Object> toolRequestEventContext =
                (Map<UUID, Object>) sensoryMem.get(TOOL_REQUEST_EVENT_CONTEXT).getValue();
        Map<String, Object> context =
                (Map<String, Object>) toolRequestEventContext.remove(requestId);
        sensoryMem.set(TOOL_REQUEST_EVENT_CONTEXT, toolRequestEventContext);
        return context;
    }

    private static void handleToolCalls(
            ChatMessage response,
            UUID initialRequestId,
            String model,
            List<ChatMessage> messages,
            RunnerContext ctx)
            throws Exception {
        updateToolCallContext(
                ctx.getSensoryMemory(),
                initialRequestId,
                messages,
                Collections.singletonList(response));

        ToolRequestEvent toolRequestEvent = new ToolRequestEvent(model, response.getToolCalls());

        saveToolRequestEventContext(
                ctx.getSensoryMemory(), toolRequestEvent.getId(), initialRequestId, model);

        ctx.sendEvent(toolRequestEvent);
    }

    /**
     * Chat with chat model.
     *
     * <p>If there is no tool calls in chat model response, send the chat response event. Otherwise,
     * generate tool request event and save the tool call context in memory.
     *
     * @param initialRequestId The request id of the initial chat request event.
     * @param messages The chat messages as llm input.
     * @param ctx The runner context this function executed in.
     */
    public static void chat(
            UUID initialRequestId, String model, List<ChatMessage> messages, RunnerContext ctx)
            throws Exception {
        BaseChatModelSetup chatModel =
                (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);

        ChatMessage response = chatModel.chat(messages, Map.of());

        if (!response.getToolCalls().isEmpty()) {
            handleToolCalls(response, initialRequestId, model, messages, ctx);
        } else {
            // clean tool call context
            clearToolCallContext(ctx.getSensoryMemory(), initialRequestId);

            ctx.sendEvent(new ChatResponseEvent(initialRequestId, response));
        }
    }

    private static void processChatRequest(ChatRequestEvent event, RunnerContext ctx)
            throws Exception {
        chat(event.getId(), event.getModel(), event.getMessages(), ctx);
    }

    private static void processToolResponse(ToolResponseEvent event, RunnerContext ctx)
            throws Exception {
        MemoryObject sensoryMem = ctx.getSensoryMemory();

        // get tool request context from memory
        Map<String, Object> context =
                removeToolRequestEventContext(sensoryMem, event.getRequestId());

        UUID initialRequestId = (UUID) context.get(INITIAL_REQUEST_ID);
        String model = (String) context.get(MODEL);

        Map<String, ToolResponse> responses = event.getResponses();
        Map<String, Boolean> success = event.getSuccess();

        List<ChatMessage> toolResponseMessages = new ArrayList<>();

        for (Map.Entry<String, ToolResponse> entry : responses.entrySet()) {
            Map<String, Object> extraArgs = new HashMap<>();
            String toolCallId = entry.getKey();
            if (event.getExternalIds().containsKey(toolCallId)) {
                extraArgs.put("externalId", event.getExternalIds().get(toolCallId));
            }

            ToolResponse response = entry.getValue();
            if (success.get(toolCallId) && response.isSuccess()) {
                toolResponseMessages.add(
                        new ChatMessage(
                                MessageRole.TOOL, String.valueOf(response.getResult()), extraArgs));
            } else {
                toolResponseMessages.add(
                        new ChatMessage(
                                MessageRole.TOOL, String.valueOf(response.getError()), extraArgs));
            }
        }

        List<ChatMessage> messages =
                updateToolCallContext(
                        ctx.getSensoryMemory(),
                        initialRequestId,
                        Collections.emptyList(),
                        toolResponseMessages);

        chat(initialRequestId, model, messages, ctx);
    }

    /**
     * Built-in action for processing chat request and tool call result.
     *
     * <p>This action will listen {@link ChatRequestEvent} and send {@link ChatResponseEvent}. If
     * there are tool calls in chat model response, it will send {@link ToolRequestEvent} and
     * feedback the correspond {@link ToolResponseEvent} to chat model.
     *
     * @param event Event this action listened, must be {@link ChatRequestEvent} or {@link
     *     ToolResponseEvent}
     * @param ctx The runner context this action executed in.
     */
    public static void processChatRequestOrToolResponse(Event event, RunnerContext ctx)
            throws Exception {
        MemoryObject sensoryMem = ctx.getSensoryMemory();
        if (event instanceof ChatRequestEvent) {
            processChatRequest((ChatRequestEvent) event, ctx);
        } else if (event instanceof ToolResponseEvent) {
            processToolResponse((ToolResponseEvent) event, ctx);
        } else {
            throw new RuntimeException(String.format("Unexpected type event %s", event));
        }
    }
}
