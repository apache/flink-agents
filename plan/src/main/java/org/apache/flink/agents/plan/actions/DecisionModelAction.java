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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.DecisionRequestEvent;
import org.apache.flink.agents.api.event.DecisionResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Executes decision requests durably and emits one correlated success or failure response. */
public final class DecisionModelAction {
    private static final Logger LOG = LoggerFactory.getLogger(DecisionModelAction.class);

    private DecisionModelAction() {}

    public static Action getDecisionModelAction() throws Exception {
        return new Action(
                "decision_model_action",
                new JavaFunction(
                        DecisionModelAction.class,
                        "processDecisionRequest",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(DecisionRequestEvent.EVENT_TYPE));
    }

    /**
     * Handles a decision request with the configured retry budget. Resource lookup failures,
     * cancellation and durable-state failures propagate instead of becoming failed decision
     * responses.
     */
    public static void processDecisionRequest(Event event, RunnerContext ctx) throws Exception {
        DecisionRequestEvent request = (DecisionRequestEvent) event;
        BaseDecisionModelSetup model =
                (BaseDecisionModelSetup)
                        ctx.getResource(request.getModel(), ResourceType.DECISION_MODEL);

        boolean async =
                ctx.getConfig().get(AgentExecutionOptions.DECISION_ASYNC)
                        && model.supportsAsyncExecution();
        int maxRetries = Math.max(0, ctx.getConfig().get(AgentExecutionOptions.MAX_RETRIES));
        int waitInterval =
                Math.max(0, ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL));
        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        FlinkAgentsMetricGroup resourceMetrics =
                actionMetrics == null
                        ? null
                        : actionMetrics.getSubGroup("model_resource", request.getModel());

        DurableCallable<InvocationOutcome> call =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return "decide";
                    }

                    @Override
                    public Class<InvocationOutcome> getResultClass() {
                        return InvocationOutcome.class;
                    }

                    @Override
                    public InvocationOutcome call() throws Exception {
                        try {
                            return new InvocationOutcome(
                                    Objects.requireNonNull(
                                            model.decide(
                                                    request.getRequest(),
                                                    request.getRequestParams()),
                                            "DecisionModel returned a null response."),
                                    null);
                        } catch (Exception error) {
                            propagateCancellation(error);
                            LOG.debug("Decision provider failed", error);
                            return new InvocationOutcome(null, errorText(error));
                        }
                    }
                };

        int retryCount = 0;
        int totalWaitSec = 0;
        DecisionResponseEvent terminal;
        try {
            while (true) {
                String executionId =
                        ExecutionReporters.started(
                                ctx,
                                ExecutionReporter.EntityTypes.LLM,
                                request.getModel(),
                                Map.of());
                // Only the provider callback converts failures. Persistence/recovery errors escape.
                InvocationOutcome outcome =
                        async ? ctx.durableExecuteAsync(call).await() : ctx.durableExecute(call);
                if (outcome.error == null) {
                    ExecutionReporters.succeeded(
                            ctx,
                            executionId,
                            ExecutionReporter.EntityTypes.LLM,
                            request.getModel(),
                            Map.of());
                    recordTokenMetrics(resourceMetrics, outcome.response);
                    terminal =
                            DecisionResponseEvent.success(
                                    request.getId(), outcome.response, retryCount, totalWaitSec);
                    break;
                }

                ExecutionReporters.failed(
                        ctx,
                        executionId,
                        ExecutionReporter.EntityTypes.LLM,
                        request.getModel(),
                        Map.of(),
                        new Exception(outcome.error),
                        ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
                if (retryCount == maxRetries) {
                    terminal =
                            DecisionResponseEvent.failed(
                                    request.getId(), outcome.error, retryCount, totalWaitSec);
                    break;
                }
                int waitSec = waitInterval * (1 << retryCount);
                retryCount++;
                LOG.warn(
                        "Decision request {} failed with error: {}, retrying {} / {}, waiting {} s.",
                        request.getId(),
                        outcome.error,
                        retryCount,
                        maxRetries,
                        waitSec);
                if (waitSec > 0) {
                    try {
                        Thread.sleep(waitSec * 1000L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw error;
                    }
                    totalWaitSec += waitSec;
                }
            }
        } finally {
            if (resourceMetrics != null && retryCount > 0) {
                resourceMetrics.getCounter("retryCount").inc(retryCount);
                resourceMetrics.getCounter("retryWaitSec").inc(totalWaitSec);
            }
        }
        ctx.sendEvent(terminal);
    }

    private static void propagateCancellation(Exception error) throws Exception {
        if (Thread.currentThread().isInterrupted() || CancellationUtils.isCancellation(error)) {
            if (CancellationUtils.isInterruption(error)) {
                Thread.currentThread().interrupt();
            }
            throw error;
        }
    }

    private static String errorText(Exception error) {
        while ((error instanceof InvocationTargetException
                        || error instanceof ExecutionException
                        || error instanceof CompletionException)
                && error.getCause() instanceof Exception) {
            error = (Exception) error.getCause();
        }
        return error.getClass().getName() + ": " + error.getMessage();
    }

    private static void recordTokenMetrics(
            @Nullable FlinkAgentsMetricGroup resourceMetrics, DecisionResult result) {
        if (resourceMetrics == null || result.getUsage() == null) {
            return;
        }
        FlinkAgentsMetricGroup metrics = resourceMetrics;
        if (result.getModel() != null && !result.getModel().isEmpty()) {
            metrics = metrics.getSubGroup("model", result.getModel());
        }
        recordTokenCount(metrics, "promptTokens", result.getUsage().getInputTokens());
        recordTokenCount(metrics, "completionTokens", result.getUsage().getOutputTokens());
        recordTokenCount(metrics, "totalTokens", result.getUsage().getTotalTokens());
    }

    private static void recordTokenCount(
            FlinkAgentsMetricGroup metrics, String name, @Nullable Long count) {
        if (count != null) {
            metrics.getCounter(name).inc(count);
        }
    }

    private static final class InvocationOutcome {
        @JsonProperty private final DecisionResult response;
        @JsonProperty private final String error;

        @JsonCreator
        private InvocationOutcome(
                @JsonProperty("response") DecisionResult response,
                @JsonProperty("error") String error) {
            this.response = response;
            this.error = error;
        }
    }
}
