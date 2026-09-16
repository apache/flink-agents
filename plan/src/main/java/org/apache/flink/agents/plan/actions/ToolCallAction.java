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
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
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
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.plan.utils.ToolResultUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

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
    static final String TOOL_CALL_DURABLE_ID = "tool-call";
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

    public static void processToolRequest(Event event, RunnerContext ctx)
            throws InterruptedException {
        ToolRequestEvent toolRequest = ToolRequestEvent.fromEvent(event);
        boolean toolCallAsync = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC);
        int toolCallParallelism = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_PARALLELISM);

        Map<String, Boolean> success = new HashMap<>();
        Map<String, String> error = new HashMap<>();
        Map<String, ToolResponse> responses = new HashMap<>();
        Map<String, String> externalIds = new HashMap<>();
        List<ToolCallExecution> executions =
                buildToolCallExecutions(toolRequest, ctx, externalIds, success, error, responses);

        // executeParallel/executeSequentially let InterruptedException propagate rather than
        // recording it as a tool error, so a cancellation here skips sendEvent below entirely:
        // no ToolResponseEvent goes out, no further chat call gets driven off a cancelled tool
        // call, and the action is never persisted as completed on the back of it.
        if (toolCallAsync && toolCallParallelism > 1 && executions.size() > 1) {
            executeParallel(executions, ctx, success, error, responses);
        } else {
            executeSequentially(executions, toolCallAsync, ctx, success, error, responses);
        }

        ctx.sendEvent(
                new ToolResponseEvent(toolRequest.getId(), responses, success, error, externalIds));
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallExecution> buildToolCallExecutions(
            ToolRequestEvent toolRequest,
            RunnerContext ctx,
            Map<String, String> externalIds,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        List<ToolCallExecution> executions = new ArrayList<>();
        for (Map<String, Object> toolCall : toolRequest.getToolCalls()) {
            String id = String.valueOf(toolCall.get("id"));
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String name = (String) function.get("name");
            Map<String, Object> arguments = (Map<String, Object>) function.get("arguments");
            Map<String, Object> mergedArguments =
                    arguments == null ? new HashMap<>() : new HashMap<>(arguments);

            if (toolCall.containsKey("original_id")) {
                externalIds.put(id, (String) toolCall.get("original_id"));
            }

            Tool tool = null;
            SubagentSetup agent = null;
            Exception preparationError = null;
            // The reserved _subagent_ prefix separates the two namespaces: tool
            // registration rejects the prefix, so a prefixed callable name can only
            // address a sub-agent. Matched once here and carried down, because resolving
            // the AGENT resource throws when the name is absent and would otherwise have
            // to be attempted for every plain tool call.
            boolean delegated = name.startsWith(SubagentSetup.CALLABLE_NAME_PREFIX);
            try {
                if (delegated) {
                    agent =
                            resolveSubagent(
                                    name.substring(SubagentSetup.CALLABLE_NAME_PREFIX.length()),
                                    ctx);
                } else {
                    tool = (Tool) ctx.getResource(name, ResourceType.TOOL);
                }
            } catch (Exception e) {
                preparationError = e;
            }

            // Injection is a tool-only contract, so a sub-agent call carries the model arguments
            // unchanged.
            if (tool != null) {
                try {
                    // Framework-owned injected args must win over model-provided values so hidden
                    // context such as tenant ids cannot be spoofed by a tool call payload.
                    mergedArguments.putAll(resolveInjectedArguments(tool, ctx));
                } catch (Exception e) {
                    preparationError = e;
                }
            }

            ToolParameters metadataParameters = new ToolParameters(mergedArguments);
            Map<String, Object> entityMetadata =
                    toolEntityMetadata(
                            toolRequest.getId(),
                            id,
                            externalIds.get(id),
                            name,
                            tool,
                            metadataParameters);
            ExecutionReporters.created(
                    ctx, ExecutionReporter.EntityTypes.TOOL, name, entityMetadata);
            boolean unresolved = tool == null && agent == null;
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
                                prepFailureMessage(name, delegated, unresolved, failure)),
                        diagnosticError,
                        success,
                        error,
                        responses);
                ExecutionReporters.failed(
                        ctx,
                        ExecutionReporter.EntityTypes.TOOL,
                        name,
                        entityMetadata,
                        failure,
                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
                continue;
            }

            final Tool toolRef = tool;
            final Map<String, Object> callArguments = mergedArguments;
            ToolCallOccurrence occurrence = new ToolCallOccurrence();
            DurableCallable<ToolResponse> callable =
                    new DurableCallable<>() {
                        @Override
                        public String getId() {
                            return TOOL_CALL_DURABLE_ID;
                        }

                        @Override
                        public Class<ToolResponse> getResultClass() {
                            return ToolResponse.class;
                        }

                        @Override
                        public ToolResponse call() throws Exception {
                            occurrence.markStarted();
                            try {
                                return toolRef.call(new ToolParameters(callArguments));
                            } finally {
                                occurrence.markFinished();
                            }
                        }
                    };
            executions.add(
                    new ToolCallExecution(
                            id, name, callable, entityMetadata, occurrence, agent, callArguments));
        }
        return executions;
    }

    private static void executeParallel(
            List<ToolCallExecution> executions,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws InterruptedException {
        // Sub-agent calls run through durable execution inside the setup, so they cannot join the
        // tool batch below; they are dispatched concurrently on their own (submit every call, then
        // await each) so that, like the batched tool calls, they overlap instead of running one by
        // one. The agent phase completes before the tool batch, so a sub-agent handle is never left
        // submitted-but-unawaited if the batch below rethrows.
        List<ToolCallExecution> agentExecutions = new ArrayList<>();
        List<ToolCallExecution> toolExecutions = new ArrayList<>();
        for (ToolCallExecution execution : executions) {
            if (execution.agent != null) {
                agentExecutions.add(execution);
            } else {
                toolExecutions.add(execution);
            }
        }
        dispatchAgentExecutions(agentExecutions, ctx, success, error, responses);
        List<DurableFuture<ToolResponse>> futures = new ArrayList<>(toolExecutions.size());
        for (ToolCallExecution execution : toolExecutions) {
            futures.add(ctx.durableExecuteAsync(execution.callable));
        }
        List<Outcome<ToolResponse>> outcomes = List.of();
        Instant resultObservedAt = null;
        Error fatalError = null;
        try {
            outcomes = ctx.gather(futures).await();
            resultObservedAt = Instant.now();
            for (int i = 0; i < outcomes.size(); i++) {
                recordOutcome(toolExecutions.get(i), outcomes.get(i), success, error, responses);
            }
        } catch (InterruptedException e) {
            // A cancellation signal, not a batch failure: propagate immediately instead of
            // recording every execution as a tool error and letting the caller send a
            // ToolResponseEvent that drives the action loop onward.
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            if (resultObservedAt == null) {
                resultObservedAt = Instant.now();
            }
            if (e instanceof CompletionException && e.getCause() instanceof Error) {
                fatalError = (Error) e.getCause();
                throw fatalError;
            }
            for (ToolCallExecution execution : toolExecutions) {
                recordExecutionException(execution, e, success, error, responses);
            }
        } catch (Error e) {
            if (resultObservedAt == null) {
                resultObservedAt = Instant.now();
            }
            fatalError = e;
            throw e;
        } finally {
            for (int i = 0; i < toolExecutions.size(); i++) {
                reportExecution(
                        toolExecutions.get(i),
                        ctx,
                        i < outcomes.size() ? outcomes.get(i) : null,
                        fatalError,
                        resultObservedAt);
            }
        }
    }

    private static void executeSequentially(
            List<ToolCallExecution> executions,
            boolean toolCallAsync,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws InterruptedException {
        for (ToolCallExecution execution : executions) {
            if (execution.agent != null) {
                dispatchAgentExecution(execution, ctx, success, error, responses);
                continue;
            }
            Outcome<ToolResponse> outcome = null;
            Instant resultObservedAt = null;
            Error fatalError = null;
            try {
                ToolResponse response =
                        toolCallAsync
                                ? ctx.durableExecuteAsync(execution.callable).await()
                                : ctx.durableExecute(execution.callable);
                resultObservedAt = Instant.now();
                outcome = Outcome.success(response);
                recordToolResponse(execution.id, response, success, error, responses);
            } catch (InterruptedException e) {
                // A cancellation signal, not a tool failure: propagate immediately instead of
                // recording it as a tool error and letting the loop move on to (or past) the
                // remaining executions and the caller send a ToolResponseEvent for it.
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
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

    private static void recordOutcome(
            ToolCallExecution execution,
            Outcome<ToolResponse> outcome,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        if (outcome.isFailure()) {
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
        Instant finishedAt = execution.occurrence.finishedAt;
        Instant startedAt = execution.occurrence.startedAt;
        if (startedAt != null && (outcome == null || !startedAt.isAfter(resultObservedAt))) {
            ExecutionReporters.startedAt(
                    ctx,
                    ExecutionReporter.EntityTypes.TOOL,
                    execution.name,
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
                    ExecutionReporter.EntityTypes.TOOL,
                    execution.name,
                    execution.entityMetadata,
                    finishedAt.toString());
        } else {
            ExecutionReporters.failedAt(
                    ctx,
                    ExecutionReporter.EntityTypes.TOOL,
                    execution.name,
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

    private static void dispatchAgentExecution(
            ToolCallExecution execution,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws InterruptedException {
        try {
            // submit() and await() already run through durable execution inside the setup, so
            // wrapping the call again here would nest durable cursors.
            SubagentResult result = execution.agent.submit(ctx, execution.agentArguments).await();
            recordAgentResult(execution, result, ctx, success, error, responses);
        } catch (InterruptedException e) {
            // A cancellation, not a sub-agent failure: propagate it exactly like the tool paths do
            // (#1111) so the caller skips sendEvent instead of folding the cancellation into a
            // tool-error response and driving a further chat call off it.
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            recordAgentFailure(execution, e, ctx, success, error, responses);
        }
    }

    /**
     * Runs the sub-agent calls concurrently rather than one by one: every {@code submit} is issued
     * first, so the async setups start their remote runs together, and each future is then awaited.
     * Submitting and awaiting both follow {@code agentExecutions} order, which is the deterministic
     * tool-call order, so the setup's id allocator hands out the same ids on every replay and the
     * durable keys stay stable. The per-call try/catch keeps the isolation the serial path had: one
     * sub-agent failing, at submit or at await, is recorded and reported without stopping the rest.
     *
     * <p>A cancellation is the one thing that is not isolated: like the tool batch (#1111), an
     * {@link InterruptedException} propagates so the caller skips sendEvent, and every handle
     * submitted but no longer going to be awaited is cancelled first, so the concurrent dispatch
     * leaves no in-flight remote run dangling on the way out.
     */
    private static void dispatchAgentExecutions(
            List<ToolCallExecution> agentExecutions,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws InterruptedException {
        // submit() runs through durable execution inside the setup, so it is not wrapped here.
        List<ToolCallExecution> submitted = new ArrayList<>(agentExecutions.size());
        List<SubagentFuture> futures = new ArrayList<>(agentExecutions.size());
        for (ToolCallExecution execution : agentExecutions) {
            try {
                futures.add(execution.agent.submit(ctx, execution.agentArguments));
                submitted.add(execution);
            } catch (InterruptedException e) {
                // Cancelled mid-submit: everything submitted so far is now never going to be
                // awaited, so cancel it before propagating like the tool paths (#1111).
                cancelFrom(futures, 0);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                recordAgentFailure(execution, e, ctx, success, error, responses);
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            ToolCallExecution execution = submitted.get(i);
            try {
                SubagentResult result = futures.get(i).await();
                recordAgentResult(execution, result, ctx, success, error, responses);
            } catch (InterruptedException e) {
                // Cancelled mid-await: this handle and every later one were submitted but will not
                // be awaited, so cancel them before propagating like the tool paths (#1111).
                cancelFrom(futures, i);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                recordAgentFailure(execution, e, ctx, success, error, responses);
            }
        }
    }

    /**
     * Requests cancellation of every handle from {@code from} onward, e.g. on a mid-batch cancel.
     */
    private static void cancelFrom(List<SubagentFuture> futures, int from) {
        for (int i = from; i < futures.size(); i++) {
            futures.get(i).cancel();
        }
    }

    private static void recordAgentFailure(
            ToolCallExecution execution,
            Exception e,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        recordExecutionException(execution, e, success, error, responses);
        ExecutionReporters.failed(
                ctx,
                ExecutionReporter.EntityTypes.TOOL,
                execution.name,
                execution.entityMetadata,
                e,
                ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
    }

    private static void recordAgentResult(
            ToolCallExecution execution,
            SubagentResult result,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses)
            throws Exception {
        if (result.isSuccess()) {
            success.put(execution.id, true);
            responses.put(
                    execution.id,
                    ToolResponse.success(
                            ToolResultUtils.toChatMessageContent(
                                    ToolResultUtils.normalizeAgentResult(
                                            result.getResult(), execution.agent.getResultType()))));
            ExecutionReporters.succeeded(
                    ctx,
                    ExecutionReporter.EntityTypes.TOOL,
                    execution.name,
                    execution.entityMetadata);
        } else {
            // The model sees why the delegation failed, so it can correct the call instead of
            // repeating it blindly; the error map keeps the same detail for observability.
            success.put(execution.id, false);
            responses.put(
                    execution.id,
                    ToolResponse.error(
                            withReason(
                                    String.format("Sub-agent %s execute failed", execution.name),
                                    result.getErrorMessage())));
            error.put(execution.id, result.getErrorMessage());
            ExecutionReporters.failed(
                    ctx,
                    ExecutionReporter.EntityTypes.TOOL,
                    execution.name,
                    execution.entityMetadata,
                    result.getException(),
                    ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
        }
    }

    private static void recordExecutionException(
            ToolCallExecution execution,
            Exception exception,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        success.put(execution.id, false);
        responses.put(
                execution.id,
                ToolResponse.error(
                        execution.agent != null
                                ? withReason(
                                        String.format(
                                                "Sub-agent %s execute failed", execution.name),
                                        exception.getMessage())
                                : String.format("Tool %s execute failed.", execution.name)));
        error.put(execution.id, exception.getMessage());
    }

    /**
     * The message the model sees when a call could not even be prepared. A rejected sub-agent call
     * carries the reason, so the model can correct the call instead of repeating it blindly.
     *
     * @param delegated whether the callable name addressed a sub-agent, decided once by the caller
     *     rather than matched again here
     */
    private static String prepFailureMessage(
            String name, boolean delegated, boolean unresolved, Exception failure) {
        if (delegated) {
            return withReason(
                    String.format("Sub-agent %s execute failed", name), failure.getMessage());
        }
        return String.format(
                unresolved ? "Tool %s does not exist." : "Tool %s execute failed.", name);
    }

    private static String withReason(String message, @Nullable String reason) {
        return reason == null || reason.isBlank() ? message + "." : message + ": " + reason;
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
        private final String name;
        private final DurableCallable<ToolResponse> callable;
        private final Map<String, Object> entityMetadata;
        private final ToolCallOccurrence occurrence;
        private final SubagentSetup agent;
        private final Map<String, Object> agentArguments;

        private ToolCallExecution(
                String id,
                String name,
                DurableCallable<ToolResponse> callable,
                Map<String, Object> entityMetadata,
                ToolCallOccurrence occurrence,
                SubagentSetup agent,
                Map<String, Object> agentArguments) {
            this.id = id;
            this.name = name;
            this.callable = callable;
            this.entityMetadata = entityMetadata;
            this.occurrence = occurrence;
            this.agent = agent;
            this.agentArguments = agentArguments;
        }
    }

    private static final class ToolCallOccurrence {
        private volatile Instant startedAt;
        private volatile Instant finishedAt;

        private void markStarted() {
            startedAt = Instant.now();
        }

        private void markFinished() {
            finishedAt = Instant.now();
        }
    }

    /**
     * Resolves a sub-agent, in one lookup: the {@code AGENT} resource is fetched once and checked
     * once here, and the caller carries the setup from then on.
     */
    private static SubagentSetup resolveSubagent(String name, RunnerContext ctx) throws Exception {
        Resource resource = ctx.getResource(name, ResourceType.AGENT);
        if (!(resource instanceof SubagentSetup)) {
            // A sub-agent owned by the other language resolves to a bridge handle here, which
            // cannot be called through this path.
            throw new IllegalArgumentException(
                    String.format(
                            "Sub-agent %s must resolve to a SubagentSetup, but was %s.",
                            name, resource == null ? "null" : resource.getClass().getName()));
        }
        return (SubagentSetup) resource;
    }

    private static Map<String, Object> toolEntityMetadata(
            UUID toolRequestEventId,
            String toolCallId,
            String externalId,
            String toolName,
            Tool tool,
            ToolParameters parameters) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, toolRequestEventId.toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, toolCallId);
        if (externalId != null) {
            metadata.put(ToolExecutionMetadataKeys.EXTERNAL_ID, externalId);
        }
        ToolType toolType = tool == null ? null : tool.getToolType();
        if (toolType != null) {
            metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, toolType.getValue());
        }
        if (tool instanceof ToolExecutionMetadataProvider) {
            Map<String, Object> extra;
            try {
                extra = ((ToolExecutionMetadataProvider) tool).getToolExecutionMetadata(parameters);
            } catch (RuntimeException e) {
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
