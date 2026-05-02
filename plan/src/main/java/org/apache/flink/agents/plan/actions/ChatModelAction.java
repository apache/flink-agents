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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.OutputSchema;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.*;

import static org.apache.flink.agents.api.agents.Agent.STRUCTURED_OUTPUT;
import static org.apache.flink.agents.plan.actions.Utils.supportAsync;

/** Built-in action for processing chat request and tool call result. */
public class ChatModelAction {
    private static final Logger LOG = LoggerFactory.getLogger(ChatModelAction.class);

    private static final String TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT";
    private static final String TOOL_REQUEST_EVENT_CONTEXT = "_TOOL_REQUEST_EVENT_CONTEXT";
    private static final String INITIAL_REQUEST_ID = "initialRequestId";
    private static final String MODEL = "model";
    private static final String OUTPUT_SCHEMA = "outputSchema";
    private static final String RETRY_STATS_CONTEXT = "_RETRY_STATS_CONTEXT";
    private static final String TOTAL_RETRY_COUNT = "totalRetryCount";
    private static final String TOTAL_RETRY_WAIT_SEC = "totalRetryWaitSec";

    private static final ObjectMapper mapper = new ObjectMapper();

    public static Action getChatModelAction() throws Exception {
        return new Action(
                "chat_model_action",
                new JavaFunction(
                        ChatModelAction.class,
                        "processChatRequestOrToolResponse",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(ChatRequestEvent.EVENT_TYPE, ToolResponseEvent.EVENT_TYPE));
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
    private static void saveToolRequestEventContext(
            MemoryObject sensoryMem,
            UUID toolRequestEventId,
            UUID initialRequestId,
            String model,
            Object outputSchema)
            throws Exception {
        Map<UUID, Object> toolRequestEventContext;
        if (sensoryMem.isExist(TOOL_REQUEST_EVENT_CONTEXT)) {
            toolRequestEventContext =
                    (Map<UUID, Object>) sensoryMem.get(TOOL_REQUEST_EVENT_CONTEXT).getValue();
        } else {
            toolRequestEventContext = new HashMap<>();
        }
        Map<String, Object> context = new HashMap<>();
        context.put(INITIAL_REQUEST_ID, initialRequestId);
        context.put(MODEL, model);
        if (outputSchema != null) {
            context.put(OUTPUT_SCHEMA, outputSchema);
        }
        toolRequestEventContext.put(toolRequestEventId, context);
        sensoryMem.set(TOOL_REQUEST_EVENT_CONTEXT, toolRequestEventContext);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getToolRequestEventContext(
            MemoryObject sensoryMem, UUID requestId) throws Exception {
        Map<UUID, Object> toolRequestEventContext =
                (Map<UUID, Object>) sensoryMem.get(TOOL_REQUEST_EVENT_CONTEXT).getValue();
        return (Map<String, Object>) toolRequestEventContext.remove(requestId);
    }

    @SuppressWarnings("unchecked")
    private static void accumulateRetryStats(
            MemoryObject sensoryMem, UUID initialRequestId, int retryCount, int retryWaitSec)
            throws Exception {
        Map<UUID, Map<String, Long>> retryStatsContext;
        if (sensoryMem.isExist(RETRY_STATS_CONTEXT)) {
            retryStatsContext =
                    (Map<UUID, Map<String, Long>>) sensoryMem.get(RETRY_STATS_CONTEXT).getValue();
        } else {
            retryStatsContext = new HashMap<>();
        }
        Map<String, Long> stats = retryStatsContext.getOrDefault(initialRequestId, new HashMap<>());
        stats.put(TOTAL_RETRY_COUNT, stats.getOrDefault(TOTAL_RETRY_COUNT, 0L) + retryCount);
        stats.put(
                TOTAL_RETRY_WAIT_SEC, stats.getOrDefault(TOTAL_RETRY_WAIT_SEC, 0L) + retryWaitSec);
        retryStatsContext.put(initialRequestId, stats);
        sensoryMem.set(RETRY_STATS_CONTEXT, retryStatsContext);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> getRetryStats(MemoryObject sensoryMem, UUID initialRequestId)
            throws Exception {
        if (!sensoryMem.isExist(RETRY_STATS_CONTEXT)) {
            return Map.of(TOTAL_RETRY_COUNT, 0L, TOTAL_RETRY_WAIT_SEC, 0L);
        }
        Map<UUID, Map<String, Long>> retryStatsContext =
                (Map<UUID, Map<String, Long>>) sensoryMem.get(RETRY_STATS_CONTEXT).getValue();
        return retryStatsContext.getOrDefault(
                initialRequestId, Map.of(TOTAL_RETRY_COUNT, 0L, TOTAL_RETRY_WAIT_SEC, 0L));
    }

    private static void recordRetryMetrics(
            RunnerContext ctx, String model, int retryCount, int totalRetryWaitSec) {
        if (retryCount <= 0) {
            return;
        }
        FlinkAgentsMetricGroup metricGroup = ctx.getActionMetricGroup();
        if (metricGroup != null) {
            FlinkAgentsMetricGroup modelGroup = metricGroup.getSubGroup(model);
            modelGroup.getCounter("retryCount").inc(retryCount);
            modelGroup.getCounter("retryWaitSec").inc(totalRetryWaitSec);
        }
    }

    private static void handleToolCalls(
            ChatMessage response,
            UUID initialRequestId,
            String model,
            List<ChatMessage> messages,
            Object outputSchema,
            RunnerContext ctx)
            throws Exception {
        updateToolCallContext(
                ctx.getSensoryMemory(),
                initialRequestId,
                messages,
                Collections.singletonList(response));

        ToolRequestEvent toolRequestEvent = new ToolRequestEvent(model, response.getToolCalls());

        saveToolRequestEventContext(
                ctx.getSensoryMemory(),
                toolRequestEvent.getId(),
                initialRequestId,
                model,
                outputSchema);

        ctx.sendEvent(toolRequestEvent);
    }

    static String cleanLlmResponse(String rawResponse) {
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```")) {
            return trimmed.replaceAll("(?s)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private static ChatMessage generateStructuredOutput(ChatMessage response, Object outputSchema)
            throws JsonProcessingException {
        String output = response.getContent();
        output = cleanLlmResponse(output);
        Object structuredOutput;
        if (outputSchema instanceof Class) {
            structuredOutput = mapper.readValue(String.valueOf(output), (Class<?>) outputSchema);
        } else if (outputSchema instanceof OutputSchema) {
            RowTypeInfo info = ((OutputSchema) outputSchema).getSchema();
            Map<String, Object> fields = mapper.readValue(String.valueOf(output), Map.class);
            structuredOutput = Row.withNames();
            for (String name : info.getFieldNames()) {
                ((Row) structuredOutput).setField(name, fields.get(name));
            }
        } else {
            throw new RuntimeException(
                    String.format("Unsupported output schema %s.", outputSchema));
        }
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put(STRUCTURED_OUTPUT, structuredOutput);
        return new ChatMessage(response.getRole(), output, extraArgs);
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
            UUID initialRequestId,
            String model,
            List<ChatMessage> messages,
            @Nullable Object outputSchema,
            RunnerContext ctx)
            throws Exception {
        BaseChatModelSetup chatModel =
                (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);

        boolean chatAsync = ctx.getConfig().get(AgentExecutionOptions.CHAT_ASYNC);

        if ((chatModel instanceof PythonChatModelSetup) && !supportAsync()) {
            chatAsync = false;
        }

        Agent.ErrorHandlingStrategy strategy =
                ctx.getConfig().get(AgentExecutionOptions.ERROR_HANDLING_STRATEGY);
        int numRetries = 0;
        int retryWaitIntervalSec = 0;
        if (strategy == Agent.ErrorHandlingStrategy.RETRY) {
            numRetries =
                    ctx.getConfig().get(AgentExecutionOptions.MAX_RETRIES) > 0
                            ? ctx.getConfig().get(AgentExecutionOptions.MAX_RETRIES)
                            : 0;
            retryWaitIntervalSec =
                    ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL) > 0
                            ? ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL)
                            : 0;
        }

        ChatMessage response = null;
        int actualRetryCount = 0;
        int totalWaitTimeSec = 0;

        DurableCallable<ChatMessage> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return "chat";
                    }

                    @Override
                    public Class<ChatMessage> getResultClass() {
                        return ChatMessage.class;
                    }

                    @Override
                    public ChatMessage call() throws Exception {
                        return chatModel.chat(messages, Map.of());
                    }
                };

        for (int attempt = 0; attempt < numRetries + 1; attempt++) {
            try {
                response =
                        chatAsync
                                ? ctx.durableExecuteAsync(callable)
                                : ctx.durableExecute(callable);
                // only generate structured output for final response.
                if (outputSchema != null && response.getToolCalls().isEmpty()) {
                    response = generateStructuredOutput(response, outputSchema);
                }
                break;
            } catch (Exception e) {
                if (strategy == Agent.ErrorHandlingStrategy.IGNORE) {
                    LOG.warn(
                            "Chat request {} failed with error: {}, ignored.", initialRequestId, e);
                    return;
                } else if (strategy == Agent.ErrorHandlingStrategy.RETRY) {
                    if (attempt == numRetries) {
                        throw e;
                    }
                    actualRetryCount = attempt + 1;
                    int currentWaitSec = retryWaitIntervalSec * (1 << (actualRetryCount - 1));
                    LOG.warn(
                            "Chat request {} failed with error: {}, retrying {} / {}, waiting {} s.",
                            initialRequestId,
                            e,
                            actualRetryCount,
                            numRetries,
                            currentWaitSec);
                    if (currentWaitSec > 0) {
                        Thread.sleep(currentWaitSec * 1000L);
                        totalWaitTimeSec += currentWaitSec;
                    }
                } else {
                    LOG.debug(
                            "Chat request {} failed, the input chat messages are {}.",
                            initialRequestId,
                            messages);
                    throw e;
                }
            }
        }

        if (actualRetryCount > 0) {
            accumulateRetryStats(
                    ctx.getSensoryMemory(), initialRequestId, actualRetryCount, totalWaitTimeSec);
        }

        if (!Objects.requireNonNull(response).getToolCalls().isEmpty()) {
            handleToolCalls(response, initialRequestId, model, messages, outputSchema, ctx);
        } else {
            Map<String, Long> retryStats = getRetryStats(ctx.getSensoryMemory(), initialRequestId);
            int totalRetryCount = retryStats.get(TOTAL_RETRY_COUNT).intValue();
            int totalRetryWaitSec = retryStats.get(TOTAL_RETRY_WAIT_SEC).intValue();

            recordRetryMetrics(
                    ctx, chatModel.getConnectionName(), totalRetryCount, totalRetryWaitSec);

            ctx.sendEvent(
                    new ChatResponseEvent(
                            initialRequestId, response, totalRetryCount, totalRetryWaitSec));
        }
    }

    private static void processChatRequest(ChatRequestEvent event, RunnerContext ctx)
            throws Exception {
        chat(event.getId(), event.getModel(), event.getMessages(), event.getOutputSchema(), ctx);
    }

    private static void processToolResponse(ToolResponseEvent event, RunnerContext ctx)
            throws Exception {
        MemoryObject sensoryMem = ctx.getSensoryMemory();

        // get tool request context from memory
        Map<String, Object> context = getToolRequestEventContext(sensoryMem, event.getRequestId());

        UUID initialRequestId = (UUID) context.get(INITIAL_REQUEST_ID);
        String model = (String) context.get(MODEL);
        Object outputSchema = context.get(OUTPUT_SCHEMA);

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

        chat(initialRequestId, model, messages, outputSchema, ctx);
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
        if (ChatRequestEvent.EVENT_TYPE.equals(event.getType())) {
            processChatRequest(ChatRequestEvent.fromEvent(event), ctx);
        } else if (ToolResponseEvent.EVENT_TYPE.equals(event.getType())) {
            processToolResponse(ToolResponseEvent.fromEvent(event), ctx);
        } else {
            throw new RuntimeException(String.format("Unexpected type event %s", event));
        }
    }
}
