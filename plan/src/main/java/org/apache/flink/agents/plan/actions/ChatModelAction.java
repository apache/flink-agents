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
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.*;

import static org.apache.flink.agents.api.agents.Agent.STRUCTURED_OUTPUT;

/**
 * Built-in action for processing chat request and tool call result.
 *
 * <h2>Model routing overview</h2>
 *
 * <p>When a {@link ChatRequestEvent} names a {@code MODEL_ROUTER} instead of a chat model, this
 * action layers five jobs on top of the normal chat path; each is localized to one place:
 *
 * <ol>
 *   <li><b>Decide</b> — {@link ModelRoutingResolver} runs the router's strategy and normalizes the
 *       result (abstain → default model; non-candidate → fail).
 *   <li><b>Durably</b> — the strategy runs inside a durable call ({@code "route:<router>"};
 *       per-request uniqueness comes from the store's (key, sequence, event, action) scoping, and
 *       the id must stay deterministic across recovery re-processing), so recovery replays the
 *       persisted decision instead of re-running a possibly non-deterministic strategy. This replay
 *       guarantee requires an action-state store to be configured ({@code actionStateStoreBackend},
 *       see {@code AgentConfigOptions#ACTION_STATE_STORE_BACKEND}); without one — the default — the
 *       decision and the chat call re-execute together on recovery, which is self-consistent but
 *       re-derives the decision.
 *   <li><b>Once per reasoning loop</b> — the selected concrete model is saved in the tool-request
 *       context and reused by tool rounds with no re-routing; the routing metadata block is parked
 *       once in an initial-request-keyed context and attached only to the loop's final response.
 *   <li><b>Fallback over retries</b> — {@link ResolvedModelRoute#attemptOrder} tries the selected
 *       model first (with its full retry budget, durable id {@code "chat:<router>:<candidate>"}),
 *       then remaining candidates in declaration order if fallback is enabled.
 *   <li><b>Observably</b> — a {@link ModelRoutingEvent} records the decision (and a second one any
 *       fallback outcome); {@link ResolvedModelRoute#buildResponseMetadata} supplies the {@code
 *       model_routing} extra args stamped on the final response; decision latency feeds {@code
 *       decision_ms} and the {@code routingDecisionLatencyMs} histogram.
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
    private static final String ROUTING_METADATA_CONTEXT = "_ROUTING_METADATA_CONTEXT";
    private static final String OUTPUT_SCHEMA = "outputSchema";
    private static final String PROMPT_ARGS = "prompt_args";
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
            Map<String, Object> promptArgs,
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
        context.put(PROMPT_ARGS, promptArgs != null ? promptArgs : Collections.emptyMap());
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
                outputSchema);

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
    static ChatMessage generateStructuredOutput(ChatMessage response, Object outputSchema)
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
                ResolvedModelRoute.direct(model),
                messages,
                promptArgs,
                outputSchema,
                ctx);
    }

    private static void chat(
            UUID initialRequestId,
            ResolvedModelRoute selection,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx)
            throws Exception {
        Agent.ErrorHandlingStrategy strategy =
                ctx.getConfig().get(AgentExecutionOptions.ERROR_HANDLING_STRATEGY);
        int numRetries = ChatModelInvoker.configuredRetries(ctx, strategy);
        int retryWaitIntervalSec = ChatModelInvoker.configuredRetryWaitSec(ctx, strategy);

        List<String> triedModels = new ArrayList<>();
        Exception lastError = null;
        for (String candidate : selection.attemptOrder()) {
            triedModels.add(candidate);
            try {
                ChatModelInvoker.ChatAttemptResult result =
                        ChatModelInvoker.chatWithRetries(
                                initialRequestId,
                                candidate,
                                selection.durableChatCallId(candidate),
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
                }

                // Routing metadata is observability-only and needed exactly once, on the final
                // response. If this response starts (or continues) a tool loop, park the block
                // in an initial-request-keyed context instead of stamping intermediate messages
                // and copying it through every tool round.
                Map<String, Object> routingMetadata =
                        selection.isRouter
                                ? selection.buildResponseMetadata(result.model, triedModels)
                                : null;
                if (!Objects.requireNonNull(result.response).getToolCalls().isEmpty()) {
                    if (routingMetadata != null) {
                        saveRoutingMetadata(
                                ctx.getSensoryMemory(), initialRequestId, routingMetadata);
                    }
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
                    if (routingMetadata == null) {
                        routingMetadata =
                                takeRoutingMetadata(ctx.getSensoryMemory(), initialRequestId);
                    }
                    if (routingMetadata != null) {
                        result.response.getExtraArgs().put("model_routing", routingMetadata);
                    }
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
            } catch (ChatModelInvoker.ChatAttemptFailed e) {
                recordAttemptRetryStats(
                        ctx, initialRequestId, e.chatModel, e.retryCount, e.totalRetryWaitSec);
                // Keep every candidate's failure: chain the previous error into the new one so
                // exhaustion surfaces A's and B's errors as suppressed of C's, not just C's.
                if (lastError != null && lastError != e.error) {
                    e.error.addSuppressed(lastError);
                }
                lastError = e.error;
                LOG.debug(
                        "Chat request {} failed for model {} with error: {}. The input chat messages are {}.",
                        initialRequestId,
                        e.model,
                        e.error.toString(),
                        messages);
            }
        }

        if (selection.isRouter && triedModels.size() > 1) {
            LOG.warn(
                    "Chat request {} exhausted all candidates {} of router '{}'; last error: {}.",
                    initialRequestId,
                    triedModels,
                    selection.requestedModel,
                    lastError == null ? null : lastError.toString());
        }
        // The reasoning loop is over; a routed loop that dies mid-way must not leak its
        // parked metadata (matters under IGNORE, where the job keeps running).
        takeRoutingMetadata(ctx.getSensoryMemory(), initialRequestId);
        if (strategy == Agent.ErrorHandlingStrategy.IGNORE) {
            LOG.warn(
                    "Chat request {} failed with error: {}, ignored.", initialRequestId, lastError);
            return;
        }
        throw Objects.requireNonNull(lastError);
    }

    /**
     * Compatibility note: retry metrics are recorded per attempt (including attempts on the failure
     * path), where previously they were recorded once with cumulative totals on the final response.
     * Totals over a completed request are unchanged; requests that ultimately fail now contribute
     * their retry counts where they previously did not.
     */
    static void recordAttemptRetryStats(
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
        String metricModel = chatModel == null ? null : chatModel.getConnectionName();
        recordRetryMetrics(
                ctx,
                metricModel == null || metricModel.isEmpty() ? "unknown" : metricModel,
                retryCount,
                retryWaitSec);
    }

    /**
     * Parks the routed request's {@code model_routing} block for the lifetime of its reasoning
     * loop, keyed by the initial request id. Stored once when the loop starts; taken (removed) once
     * when the final response is produced or the loop is abandoned.
     */
    @SuppressWarnings("unchecked")
    private static void saveRoutingMetadata(
            MemoryObject sensoryMem, UUID initialRequestId, Map<String, Object> routing)
            throws Exception {
        Map<UUID, Object> context;
        if (sensoryMem.isExist(ROUTING_METADATA_CONTEXT)) {
            context = (Map<UUID, Object>) sensoryMem.get(ROUTING_METADATA_CONTEXT).getValue();
        } else {
            context = new HashMap<>();
        }
        context.put(initialRequestId, routing);
        sensoryMem.set(ROUTING_METADATA_CONTEXT, context);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> takeRoutingMetadata(
            MemoryObject sensoryMem, UUID initialRequestId) throws Exception {
        if (!sensoryMem.isExist(ROUTING_METADATA_CONTEXT)) {
            return null;
        }
        Map<UUID, Object> context =
                (Map<UUID, Object>) sensoryMem.get(ROUTING_METADATA_CONTEXT).getValue();
        Map<String, Object> routing = (Map<String, Object>) context.remove(initialRequestId);
        if (routing != null) {
            sensoryMem.set(ROUTING_METADATA_CONTEXT, context);
        }
        return routing;
    }

    static ChatMessage generateStructuredOutputWithReport(
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
        ResolvedModelRoute selection;
        try {
            selection =
                    ModelRoutingResolver.resolve(
                            event.getId(),
                            event.getModel(),
                            event.getMessages(),
                            event.getPromptArgs(),
                            ctx);
        } catch (Exception e) {
            // Cancellation is never a routing failure to ignore — from either routing path:
            // the judge path re-sets the interrupt flag before rethrowing, and a blocking
            // custom strategy surfaces the raw InterruptedException with the flag cleared, so
            // check both. Let the action loop stop instead of dropping the request.
            if (ModelRoutingResolver.isCancellation(e) || Thread.currentThread().isInterrupted()) {
                throw e;
            }
            // A routing-strategy failure honors the same error-handling strategy as the chat
            // call itself: under IGNORE the request is dropped with a warning instead of killing
            // the job. (Retries are not applied to the decision; strategies that perform I/O are
            // expected to absorb their own transient failures.)
            if (ctx.getConfig().get(AgentExecutionOptions.ERROR_HANDLING_STRATEGY)
                    == Agent.ErrorHandlingStrategy.IGNORE) {
                LOG.warn(
                        "Routing for chat request {} (model '{}') failed with error: {}, ignored.",
                        event.getId(),
                        event.getModel(),
                        e.toString());
                return;
            }
            throw e;
        }
        chat(
                event.getId(),
                selection,
                event.getMessages(),
                event.getPromptArgs(),
                event.getOutputSchema(),
                ctx);
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

        // Tool rounds reuse the already-selected concrete model (no re-routing). If the initial
        // request was routed, its metadata block waits in ROUTING_METADATA_CONTEXT and is
        // attached when this loop produces its final response.
        chat(
                initialRequestId,
                ResolvedModelRoute.direct(model),
                messages,
                promptArgs,
                outputSchema,
                ctx);
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
    static Exception reportFailedAndPropagate(
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
