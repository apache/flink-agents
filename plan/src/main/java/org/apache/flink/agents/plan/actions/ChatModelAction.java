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
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.*;

import static org.apache.flink.agents.api.agents.Agent.STRUCTURED_OUTPUT;
import static org.apache.flink.agents.plan.actions.Utils.supportAsync;

/**
 * Built-in action for processing chat request and tool call result.
 *
 * <h2>Model routing overview</h2>
 *
 * <p>When a {@link ChatRequestEvent} names a {@code MODEL_ROUTER} instead of a chat model, this
 * action layers five jobs on top of the normal chat path; each is localized to one place:
 *
 * <ol>
 *   <li><b>Decide</b> — {@code resolveRouter} runs the router's strategy and normalizes the result
 *       (abstain → default model; non-candidate → fail).
 *   <li><b>Durably</b> — the strategy runs inside a durable call ({@code "route:<router>"};
 *       per-request uniqueness comes from the store's (key, sequence, event, action) scoping, and
 *       the id must stay deterministic across recovery re-processing), so recovery replays the
 *       persisted decision instead of re-running a possibly non-deterministic strategy.
 *   <li><b>Once per reasoning loop</b> — the selected concrete model and its routing metadata are
 *       saved in the tool-request context; tool rounds re-enter {@code chat} via {@code
 *       RoutingSelection.carried} with no re-routing.
 *   <li><b>Fallback over retries</b> — {@code candidateAttemptOrder} tries the selected model first
 *       (with its full retry budget, durable id {@code "chat:<router>:<candidate>"}), then
 *       remaining candidates in declaration order if fallback is enabled.
 *   <li><b>Observably</b> — a {@link ModelRoutingEvent} records the decision (and a second one any
 *       fallback outcome); {@code attachRoutingMetadata} stamps {@code model_routing} extra args on
 *       the response; decision latency feeds {@code decision_ms} and the {@code
 *       routingDecisionLatencyMs} histogram.
 * </ol>
 *
 * <p>A request naming a plain chat model takes the pre-routing path unchanged, including the legacy
 * durable call id {@code "chat"}.
 */
public class ChatModelAction {
    private static final Logger LOG = LoggerFactory.getLogger(ChatModelAction.class);

    private static final String TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT";
    private static final String TOOL_REQUEST_EVENT_CONTEXT = "_TOOL_REQUEST_EVENT_CONTEXT";
    private static final String INITIAL_REQUEST_ID = "initialRequestId";
    private static final String MODEL = "model";
    private static final String ROUTING = "routing";
    private static final String OUTPUT_SCHEMA = "outputSchema";
    private static final String PROMPT_ARGS = "prompt_args";
    private static final String RETRY_STATS_CONTEXT = "_RETRY_STATS_CONTEXT";
    private static final String TOTAL_RETRY_COUNT = "totalRetryCount";
    private static final String TOTAL_RETRY_WAIT_SEC = "totalRetryWaitSec";

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final class RoutingSelection {
        private final String requestedModel;
        private final String selectedModel;
        private final List<String> candidates;
        private final boolean isRouter;
        private final boolean fallbackEnabled;
        private final String decisionSource;
        @Nullable private final String reason;
        @Nullable private final Double score;
        private final Map<String, Object> metadata;

        /**
         * Routing metadata inherited from the initial routed request, stamped unchanged onto
         * responses produced by tool-call rounds (which reuse the already-selected model).
         */
        @Nullable private final Map<String, Object> carriedRouting;

        private RoutingSelection(
                String requestedModel,
                String selectedModel,
                List<String> candidates,
                boolean isRouter,
                boolean fallbackEnabled,
                String decisionSource,
                @Nullable String reason,
                @Nullable Double score,
                @Nullable Map<String, Object> metadata,
                @Nullable Map<String, Object> carriedRouting) {
            this.requestedModel = requestedModel;
            this.selectedModel = selectedModel;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.isRouter = isRouter;
            this.fallbackEnabled = fallbackEnabled;
            this.decisionSource = decisionSource;
            this.reason = reason;
            this.score = score;
            this.metadata =
                    metadata == null
                            ? Collections.emptyMap()
                            : Collections.unmodifiableMap(new HashMap<>(metadata));
            this.carriedRouting = carriedRouting;
        }

        private static RoutingSelection direct(String model) {
            return new RoutingSelection(
                    model,
                    model,
                    Collections.singletonList(model),
                    false,
                    false,
                    "direct",
                    null,
                    null,
                    null,
                    null);
        }

        private static RoutingSelection carried(String model, Map<String, Object> routing) {
            return new RoutingSelection(
                    model,
                    model,
                    Collections.singletonList(model),
                    false,
                    false,
                    "carried",
                    null,
                    null,
                    null,
                    routing);
        }
    }

    private static final class ChatAttemptResult {
        private final String model;
        private final BaseChatModelSetup chatModel;
        private final ChatMessage response;
        private final int retryCount;
        private final int totalRetryWaitSec;

        private ChatAttemptResult(
                String model,
                BaseChatModelSetup chatModel,
                ChatMessage response,
                int retryCount,
                int totalRetryWaitSec) {
            this.model = model;
            this.chatModel = chatModel;
            this.response = response;
            this.retryCount = retryCount;
            this.totalRetryWaitSec = totalRetryWaitSec;
        }
    }

    private static final class ChatAttemptFailed extends Exception {
        private final String model;
        private final BaseChatModelSetup chatModel;
        private final Exception error;
        private final int retryCount;
        private final int totalRetryWaitSec;

        private ChatAttemptFailed(
                String model,
                BaseChatModelSetup chatModel,
                Exception error,
                int retryCount,
                int totalRetryWaitSec) {
            super(error);
            this.model = model;
            this.chatModel = chatModel;
            this.error = error;
            this.retryCount = retryCount;
            this.totalRetryWaitSec = totalRetryWaitSec;
        }
    }

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
            Map<String, Object> promptArgs,
            Object outputSchema,
            @Nullable Object routingMetadata)
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
        context.put(PROMPT_ARGS, promptArgs != null ? promptArgs : Collections.emptyMap());
        if (outputSchema != null) {
            context.put(OUTPUT_SCHEMA, outputSchema);
        }
        if (routingMetadata != null) {
            context.put(ROUTING, routingMetadata);
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
            FlinkAgentsMetricGroup modelGroup = metricGroup.getSubGroup("model", model);
            modelGroup.getCounter("retryCount").inc(retryCount);
            modelGroup.getCounter("retryWaitSec").inc(totalRetryWaitSec);
        }
    }

    static void recordChatTokenMetrics(
            BaseChatModelSetup chatModel,
            ChatMessage response,
            @Nullable FlinkAgentsMetricGroup requestMetricGroup) {
        if (requestMetricGroup == null) {
            return;
        }
        Map<String, Object> extraArgs = response.getExtraArgs();
        Object modelName = extraArgs.get("model_name");
        Object promptTokens = extraArgs.get("promptTokens");
        Object completionTokens = extraArgs.get("completionTokens");
        if (modelName != null
                && !modelName.toString().isEmpty()
                && promptTokens instanceof Number
                && completionTokens instanceof Number) {
            long prompt = ((Number) promptTokens).longValue();
            long completion = ((Number) completionTokens).longValue();
            if (prompt > 0 && completion > 0) {
                chatModel.recordTokenMetrics(
                        requestMetricGroup, modelName.toString(), prompt, completion);
            }
        }
    }

    private static void handleToolCalls(
            ChatMessage response,
            UUID initialRequestId,
            String model,
            BaseChatModelSetup chatModel,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            Object outputSchema,
            RunnerContext ctx)
            throws Exception {
        updateToolCallContext(
                ctx.getSensoryMemory(),
                initialRequestId,
                messages,
                Collections.singletonList(response));

        injectBashToolArgs(response.getToolCalls(), chatModel);

        ToolRequestEvent toolRequestEvent = new ToolRequestEvent(model, response.getToolCalls());

        saveToolRequestEventContext(
                ctx.getSensoryMemory(),
                toolRequestEvent.getId(),
                initialRequestId,
                model,
                promptArgs,
                outputSchema,
                response.getExtraArgs().get("model_routing"));

        ctx.sendEvent(toolRequestEvent);
    }

    /**
     * Inject framework-controlled args ({@code allowed_commands}, {@code allowed_script_dirs}) into
     * bash tool calls so they remain hidden from the LLM. Mirrors Python {@code
     * _inject_bash_tool_args}.
     */
    @SuppressWarnings("unchecked")
    private static void injectBashToolArgs(
            List<Map<String, Object>> toolCalls, BaseChatModelSetup chatModel) throws Exception {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        List<String> scriptDirs = new ArrayList<>(chatModel.getAllowedScriptDirs());
        List<String> declaredSkills = chatModel.getSkills();
        if (declaredSkills != null
                && !declaredSkills.isEmpty()
                && chatModel.getResourceContext() != null) {
            scriptDirs.addAll(chatModel.getResourceContext().getSkillDirs(declaredSkills));
        }
        for (Map<String, Object> call : toolCalls) {
            Object function = call.get("function");
            if (!(function instanceof Map)) {
                continue;
            }
            Map<String, Object> functionMap = (Map<String, Object>) function;
            if (!Skills.BASH_TOOL.equals(functionMap.get("name"))) {
                continue;
            }
            Object argsObj = functionMap.get("arguments");
            Map<String, Object> args;
            if (argsObj instanceof Map) {
                args = (Map<String, Object>) argsObj;
            } else {
                args = new HashMap<>();
                functionMap.put("arguments", args);
            }
            args.put("allowed_commands", new ArrayList<>(chatModel.getAllowedCommands()));
            args.put("allowed_script_dirs", scriptDirs);
        }
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
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx)
            throws Exception {
        chat(
                initialRequestId,
                RoutingSelection.direct(model),
                messages,
                promptArgs,
                outputSchema,
                ctx);
    }

    private static void chat(
            UUID initialRequestId,
            RoutingSelection selection,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx)
            throws Exception {
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

        List<String> triedModels = new ArrayList<>();
        Exception lastError = null;
        for (String candidate : candidateAttemptOrder(selection)) {
            triedModels.add(candidate);
            try {
                ChatAttemptResult result =
                        chatWithRetries(
                                initialRequestId,
                                candidate,
                                durableChatCallId(selection, candidate),
                                messages,
                                promptArgs,
                                outputSchema,
                                ctx,
                                strategy,
                                numRetries,
                                retryWaitIntervalSec);
                recordAttemptRetryStats(
                        ctx,
                        initialRequestId,
                        result.chatModel,
                        result.retryCount,
                        result.totalRetryWaitSec);
                if (selection.isRouter) {
                    attachRoutingMetadata(result.response, selection, result.model, triedModels);
                    if (!result.model.equals(selection.selectedModel)) {
                        // The strategy's pick failed and another candidate answered; record the
                        // outcome in the event log, not just on the response.
                        ctx.sendEvent(
                                new ModelRoutingEvent(
                                        initialRequestId,
                                        selection.requestedModel,
                                        selection.candidates,
                                        result.model,
                                        ModelRoutingEvent.SOURCE_FALLBACK,
                                        selection.fallbackEnabled,
                                        String.format(
                                                "fallback after selected model '%s' failed",
                                                selection.selectedModel),
                                        null,
                                        selection.metadata,
                                        null));
                    }
                } else if (selection.carriedRouting != null) {
                    result.response
                            .getExtraArgs()
                            .put("model_routing", new LinkedHashMap<>(selection.carriedRouting));
                }

                if (!Objects.requireNonNull(result.response).getToolCalls().isEmpty()) {
                    handleToolCalls(
                            result.response,
                            initialRequestId,
                            result.model,
                            result.chatModel,
                            messages,
                            promptArgs,
                            outputSchema,
                            ctx);
                } else {
                    Map<String, Long> retryStats =
                            getRetryStats(ctx.getSensoryMemory(), initialRequestId);
                    int totalRetryCount = retryStats.get(TOTAL_RETRY_COUNT).intValue();
                    int totalRetryWaitSec = retryStats.get(TOTAL_RETRY_WAIT_SEC).intValue();

                    ctx.sendEvent(
                            new ChatResponseEvent(
                                    initialRequestId,
                                    result.response,
                                    totalRetryCount,
                                    totalRetryWaitSec));
                }
                return;
            } catch (ChatAttemptFailed e) {
                recordAttemptRetryStats(
                        ctx, initialRequestId, e.chatModel, e.retryCount, e.totalRetryWaitSec);
                lastError = e.error;
                LOG.debug(
                        "Chat request {} failed for model {}, the input chat messages are {}.",
                        initialRequestId,
                        e.model,
                        messages);
            }
        }

        if (strategy == Agent.ErrorHandlingStrategy.IGNORE) {
            LOG.warn(
                    "Chat request {} failed with error: {}, ignored.", initialRequestId, lastError);
            return;
        }
        throw Objects.requireNonNull(lastError);
    }

    private static ChatAttemptResult chatWithRetries(
            UUID initialRequestId,
            String model,
            String durableCallId,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx,
            Agent.ErrorHandlingStrategy strategy,
            int numRetries,
            int retryWaitIntervalSec)
            throws ChatAttemptFailed, Exception {
        BaseChatModelSetup chatModel =
                (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);
        FlinkAgentsMetricGroup requestMetricGroup = ctx.getActionMetricGroup();

        boolean chatAsync = ctx.getConfig().get(AgentExecutionOptions.CHAT_ASYNC);

        if ((chatModel instanceof PythonChatModelSetup) && !supportAsync()) {
            chatAsync = false;
        }

        int actualRetryCount = 0;
        int totalWaitTimeSec = 0;
        ChatMessage response;

        DurableCallable<ChatMessage> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return durableCallId;
                    }

                    @Override
                    public Class<ChatMessage> getResultClass() {
                        return ChatMessage.class;
                    }

                    @Override
                    public ChatMessage call() throws Exception {
                        return chatModel.chat(messages, promptArgs, Map.of());
                    }
                };
        Map<String, Object> llmMetadata =
                chatModel.getModel() == null
                        ? Map.of()
                        : Map.of(LLMExecutionMetadataKeys.MODEL, chatModel.getModel());

        for (int attempt = 0; attempt < numRetries + 1; attempt++) {
            try {
                ExecutionReporters.started(
                        ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
                try {
                    response =
                            chatAsync
                                    ? ctx.durableExecuteAsync(callable)
                                    : ctx.durableExecute(callable);
                    Objects.requireNonNull(response, "ChatModel returned a null response.");
                } catch (Throwable modelError) {
                    throw reportFailedAndPropagate(
                            ctx,
                            ExecutionReporter.EntityTypes.LLM,
                            model,
                            llmMetadata,
                            modelError,
                            ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
                }
                ExecutionReporters.succeeded(
                        ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
                recordChatTokenMetrics(chatModel, response, requestMetricGroup);
                if (outputSchema != null && response.getToolCalls().isEmpty()) {
                    response = generateStructuredOutputWithReport(ctx, response, outputSchema);
                }
                return new ChatAttemptResult(
                        model, chatModel, response, actualRetryCount, totalWaitTimeSec);
            } catch (Exception e) {
                if (strategy == Agent.ErrorHandlingStrategy.RETRY && attempt < numRetries) {
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
                    continue;
                }
                throw new ChatAttemptFailed(
                        model, chatModel, e, actualRetryCount, totalWaitTimeSec);
            }
        }
        throw new IllegalStateException("Unreachable chat retry state.");
    }

    private static void recordAttemptRetryStats(
            RunnerContext ctx,
            UUID initialRequestId,
            BaseChatModelSetup chatModel,
            int retryCount,
            int retryWaitSec)
            throws Exception {
        if (retryCount <= 0) {
            return;
        }
        accumulateRetryStats(ctx.getSensoryMemory(), initialRequestId, retryCount, retryWaitSec);
        String metricModel = chatModel.getConnectionName();
        recordRetryMetrics(
                ctx,
                metricModel == null || metricModel.isEmpty() ? "unknown" : metricModel,
                retryCount,
                retryWaitSec);
    }

    private static List<String> candidateAttemptOrder(RoutingSelection selection) {
        List<String> order = new ArrayList<>();
        order.add(selection.selectedModel);
        if (selection.isRouter && selection.fallbackEnabled) {
            for (String candidate : selection.candidates) {
                if (!candidate.equals(selection.selectedModel)) {
                    order.add(candidate);
                }
            }
        }
        return order;
    }

    private static String durableChatCallId(RoutingSelection selection, String candidate) {
        if (!selection.isRouter) {
            return "chat";
        }
        return "chat:" + selection.requestedModel + ":" + candidate;
    }

    private static void attachRoutingMetadata(
            ChatMessage response,
            RoutingSelection selection,
            String finalModel,
            List<String> triedModels) {
        boolean fallbackAttempted = !finalModel.equals(selection.selectedModel);
        List<String> fallbackModelsTried = new ArrayList<>();
        for (int i = 1; i < triedModels.size(); i++) {
            fallbackModelsTried.add(triedModels.get(i));
        }
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("router", selection.requestedModel);
        routing.put("selected_model", selection.selectedModel);
        routing.put("initial_selected_model", selection.selectedModel);
        routing.put("final_model", finalModel);
        routing.put("candidates", new ArrayList<>(selection.candidates));
        routing.put(
                "decision_source",
                fallbackAttempted ? ModelRoutingEvent.SOURCE_FALLBACK : selection.decisionSource);
        routing.put("fallback_enabled", selection.fallbackEnabled);
        routing.put("fallback_attempted", fallbackAttempted);
        routing.put("fallback_models_tried", fallbackModelsTried);
        routing.put("metadata", new LinkedHashMap<>(selection.metadata));
        if (selection.reason != null) {
            routing.put("reason", selection.reason);
        }
        if (selection.score != null) {
            routing.put("score", selection.score);
        }
        response.getExtraArgs().put("model_routing", routing);
    }

    private static ChatMessage generateStructuredOutputWithReport(
            RunnerContext ctx, ChatMessage response, Object outputSchema) throws Exception {
        ExecutionReporters.started(ctx, ExecutionReporter.EntityTypes.PARSER, STRUCTURED_OUTPUT);
        try {
            ChatMessage structuredResponse = generateStructuredOutput(response, outputSchema);
            ExecutionReporters.succeeded(
                    ctx, ExecutionReporter.EntityTypes.PARSER, STRUCTURED_OUTPUT);
            return structuredResponse;
        } catch (Throwable e) {
            throw reportFailedAndPropagate(
                    ctx,
                    ExecutionReporter.EntityTypes.PARSER,
                    STRUCTURED_OUTPUT,
                    null,
                    e,
                    ExecutionReporter.ProblemCategories.MODEL_OUTPUT_PARSE_ERROR);
        }
    }

    private static void processChatRequest(ChatRequestEvent event, RunnerContext ctx)
            throws Exception {
        RoutingSelection selection =
                resolveRouter(
                        event.getId(),
                        event.getModel(),
                        event.getMessages(),
                        event.getPromptArgs(),
                        ctx);
        chat(
                event.getId(),
                selection,
                event.getMessages(),
                event.getPromptArgs(),
                event.getOutputSchema(),
                ctx);
    }

    /**
     * If {@code model} names a {@link ModelRouter}, run its strategy (as a durable {@code "route"}
     * call so the decision replays deterministically on recovery), normalize the result (abstain ->
     * default model, non-candidate -> fail clearly), emit an observability-only {@link
     * ModelRoutingEvent}, and return the selected concrete model. Otherwise returns a direct
     * selection.
     *
     * <p>Routing runs once for the initial chat request; tool-call rounds reuse the selected
     * concrete model because it is saved in the tool-request context (see {@link
     * #handleToolCalls}), so this method is only reached with a router name on the initial request.
     */
    private static RoutingSelection resolveRouter(
            UUID requestId,
            String model,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            RunnerContext ctx)
            throws Exception {
        if (!ctx.hasResource(model, ResourceType.MODEL_ROUTER)) {
            return RoutingSelection.direct(model);
        }
        ModelRouter router = (ModelRouter) ctx.getResource(model, ResourceType.MODEL_ROUTER);
        RoutingContext routingContext =
                new RoutingContext(requestId, model, messages, promptArgs, router.getCandidates());

        DurableCallable<RoutingDecision> routeCallable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        // Deterministic across recovery re-processing: the durable store already
                        // scopes call results by (key, sequence number, event, action), so the id
                        // must NOT embed the request id — event ids are regenerated when Flink
                        // rolls back and re-processes, and a non-deterministic id turns every
                        // replay lookup into a miss (measured: 0/138 decisions replayed).
                        return "route:" + model;
                    }

                    @Override
                    public Class<RoutingDecision> getResultClass() {
                        return RoutingDecision.class;
                    }

                    @Override
                    public RoutingDecision call() throws Exception {
                        // Timed inside the durable call so the latency is persisted with the
                        // decision: a replayed run reports the original strategy wall time.
                        long start = System.nanoTime();
                        RoutingDecision decision = router.route(routingContext);
                        return decision.withDecisionMs((System.nanoTime() - start) / 1_000_000.0);
                    }
                };

        RoutingDecision decision = ctx.durableExecute(routeCallable);
        Double decisionMs = decision.getDecisionMs();
        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        if (actionMetrics != null && decisionMs != null) {
            actionMetrics.getHistogram("routingDecisionLatencyMs").update(Math.round(decisionMs));
        }

        String selectedModel;
        String decisionSource;
        if (decision.isAbstain()) {
            selectedModel = router.getDefaultModel().orElse(router.getCandidateNames().get(0));
            decisionSource = ModelRoutingEvent.SOURCE_DEFAULT;
        } else {
            selectedModel = decision.getSelectedModel();
            if (!router.isCandidate(selectedModel)) {
                throw new IllegalStateException(
                        String.format(
                                "Routing strategy for router '%s' returned non-candidate model '%s'; candidates are %s.",
                                model, selectedModel, router.getCandidateNames()));
            }
            decisionSource = ModelRoutingEvent.SOURCE_STRATEGY;
        }

        ctx.sendEvent(
                new ModelRoutingEvent(
                        requestId,
                        model,
                        router.getCandidateNames(),
                        selectedModel,
                        decisionSource,
                        router.isFallbackEnabled(),
                        decision.getReason(),
                        decision.getScore(),
                        decision.getMetadata(),
                        decisionMs));
        return new RoutingSelection(
                model,
                selectedModel,
                router.getCandidateNames(),
                true,
                router.isFallbackEnabled(),
                decisionSource,
                decision.getReason(),
                decision.getScore(),
                decision.getMetadata(),
                null);
    }

    @SuppressWarnings("unchecked")
    private static void processToolResponse(ToolResponseEvent event, RunnerContext ctx)
            throws Exception {
        MemoryObject sensoryMem = ctx.getSensoryMemory();

        // get tool request context from memory
        Map<String, Object> context = getToolRequestEventContext(sensoryMem, event.getRequestId());

        UUID initialRequestId = (UUID) context.get(INITIAL_REQUEST_ID);
        String model = (String) context.get(MODEL);
        Map<String, Object> promptArgs =
                (Map<String, Object>) context.getOrDefault(PROMPT_ARGS, Map.of());
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

        // Tool rounds reuse the already-selected concrete model (no re-routing); if the initial
        // request was routed, carry its routing metadata onto the eventual final response.
        Map<String, Object> routingMetadata = (Map<String, Object>) context.get(ROUTING);
        RoutingSelection selection =
                routingMetadata == null
                        ? RoutingSelection.direct(model)
                        : RoutingSelection.carried(model, routingMetadata);
        chat(initialRequestId, selection, messages, promptArgs, outputSchema, ctx);
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

    /**
     * Reports a nested execution failure, then always throws the original failure. The Exception
     * return type exists so callers must {@code throw} the result and cannot fall through.
     */
    private static Exception reportFailedAndPropagate(
            RunnerContext ctx,
            String entityType,
            String entityName,
            @Nullable Map<String, Object> entityMetadata,
            Throwable error,
            String problemCategory)
            throws Exception {
        if (entityMetadata == null) {
            ExecutionReporters.failed(ctx, entityType, entityName, error, problemCategory);
        } else {
            ExecutionReporters.failed(
                    ctx, entityType, entityName, entityMetadata, error, problemCategory);
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
        if (error instanceof Exception) {
            throw (Exception) error;
        }
        throw new RuntimeException(error);
    }
}
