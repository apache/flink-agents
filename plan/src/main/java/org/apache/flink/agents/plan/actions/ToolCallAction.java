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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolExecutionMetadataProvider;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.subagent.SubagentConstants;
import org.apache.flink.agents.plan.tools.AsyncTool;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.plan.tools.ToolCallContext;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Built-in action for processing tool call. */
public class ToolCallAction {
    static final String TOOL_CALL_DURABLE_ID = "tool-call:";
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallAction.class);

    public static Action getToolCallAction() throws Exception {
        return new Action(
                "tool_call_action",
                new JavaFunction(
                        ToolCallAction.class,
                        "processToolRequest",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(ToolRequestEvent.EVENT_TYPE));
    }

    public static void processToolRequest(Event event, RunnerContext ctx) throws Exception {
        // Built-in events reach an action as their concrete subclass (restored at the JSON
        // boundary), so the dispatched ToolRequestEvent is cast directly.
        ToolRequestEvent toolRequest = (ToolRequestEvent) event;
        boolean toolCallAsync = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC);
        int toolCallParallelism =
                ctx.getConfig().get(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM);

        Map<String, Boolean> success = new HashMap<>();
        Map<String, String> error = new HashMap<>();
        Map<String, ToolResponse> responses = new HashMap<>();
        List<ToolCallExecution> executions =
                buildToolCallExecutions(toolRequest, ctx, success, error, responses);

        // executeParallel/executeSequentially let cancellation propagate rather than
        // recording it as a tool error, so a cancellation here skips sendEvent below entirely:
        // no ToolResponseEvent goes out, no further chat call gets driven off a cancelled tool
        // call, and the action is never persisted as completed on the back of it.
        if (toolCallAsync && toolCallParallelism > 1 && executions.size() > 1) {
            executeParallel(executions, ctx, success, error, responses);
        } else {
            executeSequentially(executions, ctx, success, error, responses);
        }

        ctx.sendEvent(
                new ToolResponseEvent(toolRequest.getId(), responses, success, error)
                        .withUserContext(toolRequest.getUserContext()));
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallExecution> buildToolCallExecutions(
            ToolRequestEvent toolRequest,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws Exception {
        List<ToolCallExecution> executions = new ArrayList<>();
        for (ToolCallBlock toolCall : toolRequest.getToolCalls()) {
            String id = toolCall.getCallId();
            String name = toolCall.getName();
            Map<String, Object> arguments = toolCall.getInput();
            Map<String, Object> mergedArguments = new HashMap<>(arguments);

            Tool tool = null;
            Exception preparationError = null;
            try {
                tool = (Tool) ctx.getResource(name, ResourceType.TOOL);
            } catch (Exception e) {
                rethrowFrameworkFaultOrCancellation(e);
                preparationError = e;
            }

            // Injection is a tool-only contract, so a sub-agent call carries the model arguments
            // unchanged.
            if (tool != null) {
                try {
                    mergedArguments.putAll(resolveInjectedArguments(tool, ctx));
                } catch (Exception e) {
                    rethrowFrameworkFaultOrCancellation(e);
                    preparationError = e;
                }
            }

            ToolParameters metadataParameters = new ToolParameters(mergedArguments);
            Map<String, Object> entityMetadata =
                    toolEntityMetadata(toolRequest.getId(), id, name, tool, metadataParameters);
            // Only resolved calls in the reserved namespace use the subagent reporting scope.
            boolean subagent = tool != null && name.startsWith(SubagentConstants.TOOL_NAME_PREFIX);
            String reportEntityType =
                    subagent
                            ? ExecutionReporter.EntityTypes.SUBAGENT
                            : ExecutionReporter.EntityTypes.TOOL;
            String reportEntityName =
                    subagent ? name.substring(SubagentConstants.TOOL_NAME_PREFIX.length()) : name;
            String traceExecutionId =
                    ExecutionReporters.created(
                            ctx, reportEntityType, reportEntityName, entityMetadata);
            boolean unresolved = tool == null;
            if (unresolved || preparationError != null) {
                Exception failure =
                        preparationError != null
                                ? preparationError
                                : new IllegalArgumentException("Tool does not exist.");
                String diagnosticError = failure.getMessage();
                if (diagnosticError == null && tool == null) {
                    diagnosticError = "Tool does not exist.";
                }
                recordInlineResponse(
                        id,
                        ToolResponse.error(
                                tool == null
                                        ? String.format("Failed to resolve tool %s.", name)
                                        : failureMessage(name, failure)),
                        diagnosticError,
                        success,
                        error,
                        responses);
                ExecutionReporters.failed(
                        ctx,
                        traceExecutionId,
                        reportEntityType,
                        reportEntityName,
                        entityMetadata,
                        failure,
                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
                continue;
            }

            executions.add(
                    new ToolCallExecution(
                            id,
                            traceExecutionId,
                            reportEntityType,
                            reportEntityName,
                            name,
                            tool,
                            new ToolParameters(mergedArguments),
                            entityMetadata,
                            new ToolCallContext(
                                    id,
                                    ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC))));
        }
        return executions;
    }

    private static AsyncFuture<ToolResponse> createCallFuture(
            ToolCallExecution execution, RunnerContext ctx) throws Exception {
        if (execution.tool instanceof AsyncTool) {
            return ((AsyncTool) execution.tool)
                    .callAsync(ctx, execution.parameters, execution.callContext);
        }
        DurableCallable<ToolResponse> call =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return TOOL_CALL_DURABLE_ID + execution.callContext.getCallId();
                    }

                    @Override
                    public Class<ToolResponse> getResultClass() {
                        return ToolResponse.class;
                    }

                    @Override
                    public ToolResponse call() {
                        execution.callContext.started();
                        try {
                            return execution.tool.call(execution.parameters);
                        } finally {
                            execution.callContext.finished();
                        }
                    }
                };
        return execution.callContext.isAsync()
                ? ctx.durableExecuteAsync(call)
                : () -> ctx.durableExecute(call);
    }

    private static void executeParallel(
            List<ToolCallExecution> executions,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws Exception {
        List<ToolCallExecution> prepared = new ArrayList<>();
        List<AsyncFuture<ToolResponse>> futures = new ArrayList<>();
        for (ToolCallExecution execution : executions) {
            try {
                futures.add(createCallFuture(execution, ctx));
                prepared.add(execution);
            } catch (Exception e) {
                if (CancellationUtils.isCancellation(e)
                        || UncapturableException.isUncapturable(e)) {
                    cancelPrepared(prepared, e);
                }
                rethrowFrameworkFaultOrCancellation(e);
                recordExecutionException(execution, e, success, error, responses);
                reportExecution(execution, ctx, Outcome.failure(e), null, Instant.now());
            } catch (Error e) {
                cancelPrepared(prepared, e);
                throw e;
            }
        }
        List<Outcome<ToolResponse>> outcomes = List.of();
        Instant resultObservedAt = null;
        Error fatalError = null;
        try {
            outcomes = ctx.gather(futures).await();
            resultObservedAt = Instant.now();
            for (int i = 0; i < outcomes.size(); i++) {
                recordOutcome(prepared.get(i), outcomes.get(i), success, error, responses);
            }
        } catch (Exception e) {
            cancelPrepared(prepared, e);
            rethrowFrameworkFaultOrCancellation(e);
            if (resultObservedAt == null) {
                resultObservedAt = Instant.now();
            }
            if (e instanceof CompletionException && e.getCause() instanceof Error) {
                fatalError = (Error) e.getCause();
                throw fatalError;
            }
            for (ToolCallExecution execution : prepared) {
                recordExecutionException(execution, e, success, error, responses);
            }
        } catch (Error e) {
            cancelPrepared(prepared, e);
            if (resultObservedAt == null) {
                resultObservedAt = Instant.now();
            }
            fatalError = e;
            throw e;
        } finally {
            for (int i = 0; i < prepared.size(); i++) {
                reportExecution(
                        prepared.get(i),
                        ctx,
                        i < outcomes.size() ? outcomes.get(i) : null,
                        fatalError,
                        resultObservedAt);
            }
        }
    }

    private static void cancelPrepared(List<ToolCallExecution> prepared, Throwable failure) {
        for (ToolCallExecution active : prepared) {
            try {
                active.callContext.cancel();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    private static void executeSequentially(
            List<ToolCallExecution> executions,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws Exception {
        for (ToolCallExecution execution : executions) {
            Outcome<ToolResponse> outcome = null;
            Instant resultObservedAt = null;
            Error fatalError = null;
            try {
                ToolResponse response = createCallFuture(execution, ctx).await();
                resultObservedAt = Instant.now();
                outcome = Outcome.success(response);
                recordToolResponse(execution.id, response, success, error, responses);
            } catch (Exception e) {
                rethrowFrameworkFaultOrCancellation(e);
                if (resultObservedAt == null) {
                    resultObservedAt = Instant.now();
                }
                outcome = Outcome.failure(e);
                recordExecutionException(execution, e, success, error, responses);
            } catch (Error e) {
                if (resultObservedAt == null) {
                    resultObservedAt = Instant.now();
                }
                fatalError = e;
                throw e;
            } finally {
                reportExecution(execution, ctx, outcome, fatalError, resultObservedAt);
            }
        }
    }

    private static void rethrowFrameworkFaultOrCancellation(Exception failure) throws Exception {
        if (CancellationUtils.isCancellation(failure)) {
            if (CancellationUtils.isInterruption(failure)) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        }
        UncapturableException.rethrowIfPresent(failure);
    }

    private static void recordOutcome(
            ToolCallExecution execution,
            Outcome<ToolResponse> outcome,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws Exception {
        if (outcome.isFailure()) {
            rethrowFrameworkFaultOrCancellation(outcome.getError());
            recordExecutionException(execution, outcome.getError(), success, error, responses);
        } else {
            recordToolResponse(execution.id, outcome.getValue(), success, error, responses);
        }
    }

    private static void reportExecution(
            ToolCallExecution execution,
            RunnerContext ctx,
            Outcome<ToolResponse> outcome,
            Error fatalError,
            Instant resultObservedAt) {
        Instant finishedAt = execution.callContext.getFinishedAt();
        Instant startedAt = execution.callContext.getStartedAt();
        if (startedAt != null && (outcome == null || !startedAt.isAfter(resultObservedAt))) {
            ExecutionReporters.startedAt(
                    ctx,
                    execution.traceExecutionId,
                    execution.reportEntityType,
                    execution.reportEntityName,
                    execution.entityMetadata,
                    startedAt.toString());
        }
        if (outcome == null && fatalError == null) {
            return;
        }
        // A timed-out callable may finish after the Action already received its failure.
        if (finishedAt == null || finishedAt.isAfter(resultObservedAt)) {
            finishedAt = resultObservedAt;
        }
        Throwable failure =
                outcome == null
                        ? fatalError
                        : outcome.isFailure()
                                ? outcome.getError()
                                : toolResponseFailure(outcome.getValue());

        if (failure == null) {
            ExecutionReporters.succeededAt(
                    ctx,
                    execution.traceExecutionId,
                    execution.reportEntityType,
                    execution.reportEntityName,
                    execution.entityMetadata,
                    finishedAt.toString());
        } else {
            ExecutionReporters.failedAt(
                    ctx,
                    execution.traceExecutionId,
                    execution.reportEntityType,
                    execution.reportEntityName,
                    execution.entityMetadata,
                    failure,
                    ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED,
                    finishedAt.toString());
        }
    }

    private static Throwable toolResponseFailure(ToolResponse response) {
        if (response == null) {
            return new IllegalStateException("Tool returned a null response.");
        }
        return response.isError() ? new RuntimeException(response.getError()) : null;
    }

    private static void recordInlineResponse(
            String id,
            ToolResponse response,
            String diagnosticError,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        recordToolResponse(id, response, success, error, responses);
        if (diagnosticError != null) {
            error.put(id, diagnosticError);
        }
    }

    private static void recordExecutionException(
            ToolCallExecution execution,
            Exception exception,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        UncapturableException.rethrowIfPresent(exception);
        success.put(execution.id, false);
        responses.put(execution.id, ToolResponse.error(failureMessage(execution.name, exception)));
        error.put(execution.id, exception.getMessage());
    }

    private static String failureMessage(String name, Exception failure) {
        String prefix = "Tool " + name + " execution failed";
        String reason = failure.getMessage();
        return reason == null || reason.isBlank() ? prefix + "." : prefix + ": " + reason;
    }

    private static void recordToolResponse(
            String id,
            ToolResponse response,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        success.put(id, response.isSuccess());
        responses.put(id, response);
        if (!response.isSuccess() && response.getError() != null) {
            error.put(id, response.getError());
        }
    }

    private static final class ToolCallExecution {
        private final String id;
        private final String traceExecutionId;
        private final String reportEntityType;
        private final String reportEntityName;
        private final String name;
        private final Tool tool;
        private final ToolParameters parameters;
        private final Map<String, Object> entityMetadata;
        private final ToolCallContext callContext;

        private ToolCallExecution(
                String id,
                String traceExecutionId,
                String reportEntityType,
                String reportEntityName,
                String name,
                Tool tool,
                ToolParameters parameters,
                Map<String, Object> entityMetadata,
                ToolCallContext callContext) {
            this.id = id;
            this.traceExecutionId = traceExecutionId;
            this.reportEntityType = reportEntityType;
            this.reportEntityName = reportEntityName;
            this.name = name;
            this.tool = tool;
            this.parameters = parameters;
            this.entityMetadata = entityMetadata;
            this.callContext = callContext;
        }
    }

    private static Map<String, Object> toolEntityMetadata(
            UUID toolRequestEventId,
            String toolCallId,
            String toolName,
            Tool tool,
            ToolParameters parameters) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, toolRequestEventId.toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, toolCallId);
        ToolType toolType = tool == null ? null : tool.getToolType();
        if (toolType != null) {
            metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, toolType.getValue());
        }
        if (tool instanceof ToolExecutionMetadataProvider) {
            Map<String, Object> extra;
            try {
                extra = ((ToolExecutionMetadataProvider) tool).getToolExecutionMetadata(parameters);
            } catch (RuntimeException e) {
                UncapturableException.rethrowIfPresent(e);
                LOG.debug("Failed to collect execution metadata for tool {}.", toolName, e);
                extra = Map.of();
            }
            if (extra != null && !extra.isEmpty()) {
                mergeSupplementalMetadata(metadata, extra);
            }
        }
        return metadata;
    }

    private static void mergeSupplementalMetadata(
            Map<String, Object> target, Map<String, Object> supplemental) {
        for (Map.Entry<String, Object> entry : supplemental.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Map<String, Object> resolveInjectedArguments(Tool tool, RunnerContext ctx)
            throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (!(tool instanceof FunctionTool)) {
            return result;
        }
        FunctionTool functionTool = (FunctionTool) tool;
        for (Map.Entry<String, ToolParameterInjection> entry :
                functionTool.getInjectedArgs().entrySet()) {
            result.put(entry.getKey(), resolveInjectedArgument(entry.getValue(), ctx));
        }
        return result;
    }

    private static Object resolveInjectedArgument(
            ToolParameterInjection injection, RunnerContext ctx) throws Exception {
        String key = injection.getKey();
        ToolParameterSource source = injection.getSource();
        switch (source) {
            case CONFIG:
                Object value = ctx.getConfig().get(new ConfigOption<>(key, Object.class, null));
                if (value == null) {
                    throw new IllegalArgumentException(
                            "Missing config for injected tool parameter: " + key);
                }
                return value;
            case SENSORY_MEMORY:
                return getMemoryValue(ctx.getSensoryMemory(), "sensory_memory", key);
            case SHORT_TERM_MEMORY:
                return getMemoryValue(ctx.getShortTermMemory(), "short_term_memory", key);
            default:
                throw new IllegalArgumentException("Unsupported tool parameter source: " + source);
        }
    }

    private static Object getMemoryValue(MemoryObject memory, String source, String path)
            throws Exception {
        if (memory == null) {
            throw new IllegalStateException(
                    "Cannot inject tool parameter from "
                            + source
                            + " because memory is not initialized.");
        }
        if (!memory.isExist(path)) {
            throw new IllegalArgumentException(
                    "Missing memory path for injected tool parameter: " + path);
        }
        MemoryObject value = memory.get(path);
        if (value == null || value.isNestedObject()) {
            throw new IllegalArgumentException(
                    "Memory path for injected tool parameter must reference a value: " + path);
        }
        return value.getValue();
    }
}
