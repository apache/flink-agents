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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * The one implementation of a durable, retried, reported and metered decision-model call, shared by
 * the built-in {@link DecisionModelAction} and the decision-model routing strategy. Callers supply
 * the durable id and decide what to do with the terminal outcome; this class owns the retry loop,
 * the execution reports, the token metrics and the durable record shape, so the two call sites
 * cannot drift.
 */
public final class DecisionModelInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionModelInvoker.class);

    private DecisionModelInvoker() {}

    /** The terminal outcome of the retry loop: a result, or the last error after the budget. */
    public static final class DecisionAttemptResult {
        @Nullable public final DecisionResult response;
        @Nullable public final String error;
        public final int retryCount;
        public final int totalWaitSec;

        DecisionAttemptResult(
                @Nullable DecisionResult response,
                @Nullable String error,
                int retryCount,
                int totalWaitSec) {
            this.response = response;
            this.error = error;
            this.retryCount = retryCount;
            this.totalWaitSec = totalWaitSec;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /** The durable record of one decision attempt: the provider's result or its error text. */
    public static final class DecisionOutcome {
        @JsonProperty private final DecisionResult response;
        @JsonProperty private final String error;

        @JsonCreator
        public DecisionOutcome(
                @JsonProperty("response") @Nullable DecisionResult response,
                @JsonProperty("error") @Nullable String error) {
            this.response = response;
            this.error = error;
        }
    }

    /**
     * Runs {@code setup.decide(request, requestParams)} under {@code durableCallId} with the
     * configured retry budget. Only the provider callback converts failures into a retryable
     * outcome; persistence and recovery errors escape, and cancellation propagates immediately.
     * Token metrics for a successful result land on {@code model_resource/<modelName>}; when {@code
     * recordRetryMetrics} is set, the retry counters do too (a caller that accumulates retry stats
     * through {@link ChatModelAction#recordAttemptRetryStats} passes {@code false}).
     *
     * @param label what is being decided, for the retry log line
     */
    public static DecisionAttemptResult decideWithRetries(
            RunnerContext ctx,
            String modelName,
            BaseDecisionModelSetup setup,
            DecisionRequest request,
            Map<String, Object> requestParams,
            String durableCallId,
            String label,
            boolean recordRetryMetrics)
            throws Exception {
        boolean async =
                ctx.getConfig().get(AgentExecutionOptions.DECISION_ASYNC)
                        && setup.supportsAsyncExecution();
        int maxRetries = Math.max(0, ctx.getConfig().get(AgentExecutionOptions.MAX_RETRIES));
        int waitInterval =
                Math.max(0, ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL));
        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        FlinkAgentsMetricGroup resourceMetrics =
                actionMetrics == null
                        ? null
                        : actionMetrics.getSubGroup("model_resource", modelName);

        DurableCallable<DecisionOutcome> call =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return durableCallId;
                    }

                    @Override
                    public Class<DecisionOutcome> getResultClass() {
                        return DecisionOutcome.class;
                    }

                    @Override
                    public DecisionOutcome call() throws Exception {
                        try {
                            return new DecisionOutcome(
                                    Objects.requireNonNull(
                                            setup.decide(request, requestParams),
                                            "DecisionModel returned a null response."),
                                    null);
                        } catch (Exception error) {
                            CancellationUtils.rethrowIfCancellation(error);
                            LOG.debug("Decision provider failed", error);
                            return new DecisionOutcome(null, ChatModelInvoker.errorText(error));
                        }
                    }
                };

        int retryCount = 0;
        int totalWaitSec = 0;
        try {
            while (true) {
                String executionId =
                        ExecutionReporters.started(
                                ctx, ExecutionReporter.EntityTypes.LLM, modelName, Map.of());
                // Only the provider callback converts failures. Persistence/recovery errors escape.
                DecisionOutcome outcome;
                try {
                    outcome =
                            async
                                    ? ctx.durableExecuteAsync(call).await()
                                    : ctx.durableExecute(call);
                } catch (InterruptedException e) {
                    // A cancellation signal, not a model failure: restore the interrupt status
                    // and propagate immediately.
                    Thread.currentThread().interrupt();
                    throw e;
                }
                if (outcome.error == null) {
                    ExecutionReporters.succeeded(
                            ctx,
                            executionId,
                            ExecutionReporter.EntityTypes.LLM,
                            modelName,
                            Map.of());
                    recordTokenMetrics(resourceMetrics, outcome.response);
                    return new DecisionAttemptResult(
                            outcome.response, null, retryCount, totalWaitSec);
                }
                ExecutionReporters.failed(
                        ctx,
                        executionId,
                        ExecutionReporter.EntityTypes.LLM,
                        modelName,
                        Map.of(),
                        new Exception(outcome.error),
                        ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
                if (retryCount == maxRetries) {
                    return new DecisionAttemptResult(null, outcome.error, retryCount, totalWaitSec);
                }
                int waitSec = waitInterval * (1 << retryCount);
                retryCount++;
                LOG.warn(
                        "Decision request {} failed with error: {}, retrying {} / {}, waiting {} s.",
                        label,
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
            if (recordRetryMetrics && resourceMetrics != null && retryCount > 0) {
                resourceMetrics.getCounter("retryCount").inc(retryCount);
                resourceMetrics.getCounter("retryWaitSec").inc(totalWaitSec);
            }
        }
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
}
