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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.DecisionRequestEvent;
import org.apache.flink.agents.api.event.DecisionResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.plan.resource.python.PythonDecisionModelConnection;
import org.apache.flink.agents.plan.resource.python.PythonDecisionModelSetup;
import org.apache.flink.agents.plan.resource.python.PythonResourceAdapter;
import org.apache.flink.metrics.SimpleCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionModelActionTest {
    private static final String MODEL = "review-model";

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void succeedsThroughConfiguredDurablePath(boolean async) throws Exception {
        Harness h = new Harness(async, 0);
        when(h.model.decide(any(), anyMap()))
                .thenReturn(result("provider-model", new DecisionResult.TokenUsage(7L, 2L, 9L)));

        h.run();

        assertThat(h.response().isSuccess()).isTrue();
        assertThat(h.response().getRequestId()).isEqualTo(h.request.getId());
        assertThat(h.response().getResponse().getModel()).isEqualTo("provider-model");
        assertThat(h.response().getRetryCount()).isZero();
        assertThat(h.response().getTotalRetryWaitSec()).isZero();
        assertThat(h.providerCounters.get("promptTokens").getCount()).isEqualTo(7);
        assertThat(h.providerCounters.get("completionTokens").getCount()).isEqualTo(2);
        assertThat(h.providerCounters.get("totalTokens").getCount()).isEqualTo(9);
        assertThat(h.resourceCounters).isEmpty();
        verify(h.model).decide(h.request.getRequest(), h.request.getRequestParams());
        ArgumentCaptor<String> executionId = ArgumentCaptor.forClass(String.class);
        verify(h.reporter)
                .reportExecutionStarted(executionId.capture(), eq("llm"), eq(MODEL), eq(Map.of()));
        assertThat(executionId.getValue()).isNotBlank();
        verify(h.reporter).reportExecutionSucceeded(executionId.getValue(), "llm", MODEL, Map.of());
        verify(h.reporter, never())
                .reportExecutionFailed(
                        anyString(), anyString(), anyString(), anyMap(), any(), any());
        verify(h.ctx, times(async ? 0 : 1))
                .durableExecute(argThat(call -> "decide".equals(call.getId())));
        verify(h.ctx, times(async ? 1 : 0))
                .durableExecuteAsync(argThat(call -> "decide".equals(call.getId())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesOrdinaryFailureThenEmitsOneSuccess(boolean async) throws Exception {
        Harness h = new Harness(async, 2);
        when(h.model.decide(any(), anyMap()))
                .thenThrow(new IllegalStateException("temporarily unavailable"))
                .thenReturn(result(null, null));

        h.run();

        assertThat(h.response().isSuccess()).isTrue();
        assertThat(h.response().getRetryCount()).isEqualTo(1);
        assertThat(h.resourceCounters.get("retryCount").getCount()).isEqualTo(1);
        assertThat(h.resourceCounters.get("retryWaitSec").getCount()).isZero();
        verify(h.model, times(2)).decide(h.request.getRequest(), h.request.getRequestParams());
        ArgumentCaptor<String> executionIds = ArgumentCaptor.forClass(String.class);
        verify(h.reporter, times(2))
                .reportExecutionStarted(executionIds.capture(), eq("llm"), eq(MODEL), eq(Map.of()));
        assertThat(executionIds.getAllValues()).doesNotHaveDuplicates();
        assertThat(executionIds.getAllValues()).allSatisfy(id -> assertThat(id).isNotBlank());
        verify(h.reporter)
                .reportExecutionSucceeded(
                        executionIds.getAllValues().get(1), "llm", MODEL, Map.of());
        verify(h.reporter)
                .reportExecutionFailed(
                        eq(executionIds.getAllValues().get(0)),
                        eq("llm"),
                        eq(MODEL),
                        eq(Map.of()),
                        any(),
                        eq("model_call_failed"));
    }

    @Test
    void requestParametersAreSpecificToEachCall() throws Exception {
        Harness h = new Harness(false, 0);
        when(h.model.decide(any(), anyMap())).thenReturn(result(null, null));
        DecisionRequestEvent second =
                new DecisionRequestEvent(
                        MODEL, h.request.getRequest(), Map.of("safety_identifier", "other-user"));

        h.run();
        DecisionModelAction.processDecisionRequest(second, h.ctx);

        verify(h.model).decide(h.request.getRequest(), h.request.getRequestParams());
        verify(h.model).decide(second.getRequest(), second.getRequestParams());
        assertThat(h.events).hasSize(2);
    }

    @Test
    void exhaustedRetriesEmitOneCorrelatedFailure() throws Exception {
        Harness h = new Harness(false, 2);
        when(h.model.decide(any(), anyMap()))
                .thenThrow(new IllegalArgumentException("bad response"));

        h.run();

        assertThat(h.response().isFailed()).isTrue();
        assertThat(h.response().getRequestId()).isEqualTo(h.request.getId());
        assertThat(h.response().getError())
                .isEqualTo("java.lang.IllegalArgumentException: bad response");
        assertThat(h.response().getRetryCount()).isEqualTo(2);
        assertThat(h.resourceCounters.get("retryCount").getCount()).isEqualTo(2);
        verify(h.model, times(3)).decide(any(), anyMap());
        verify(h.reporter, times(3))
                .reportExecutionFailed(
                        anyString(),
                        eq("llm"),
                        eq(MODEL),
                        eq(Map.of()),
                        any(),
                        eq("model_call_failed"));
        verify(h.reporter, never())
                .reportExecutionSucceeded(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void nullProviderResultIsPersistedAsFailure() throws Exception {
        Harness h = new Harness(false, 0);
        when(h.model.decide(any(), anyMap())).thenReturn(null);

        h.run();

        assertThat(h.response().isFailed()).isTrue();
        assertThat(h.response().getError()).contains("DecisionModel returned a null response.");
        assertThat(h.stored).hasSize(1);
        verify(h.model).decide(any(), anyMap());
    }

    @Test
    void refusalIsSuccessfulAndDoesNotConsumeRetryBudget() throws Exception {
        Harness h = new Harness(false, 3);
        when(h.model.decide(any(), anyMap()))
                .thenReturn(
                        new DecisionResult(
                                Map.of(
                                        "damaged",
                                        new DecisionResult.RefusalAnswer("not enough evidence")),
                                null,
                                null,
                                Map.of()));

        h.run();

        assertThat(h.response().isSuccess()).isTrue();
        assertThat(h.response().getResponse().getAnswers().get("damaged"))
                .isInstanceOf(DecisionResult.RefusalAnswer.class);
        assertThat(h.response().getRetryCount()).isZero();
        verify(h.model).decide(any(), anyMap());
    }

    @Test
    void resourceLookupFailurePropagatesWithoutProviderAttempt() throws Exception {
        Harness h = new Harness(false, 2);
        Exception failure = new IllegalArgumentException("unknown model");
        when(h.ctx.getResource(MODEL, ResourceType.DECISION_MODEL)).thenThrow(failure);

        assertThatThrownBy(h::run).isSameAs(failure);

        verify(h.ctx, never()).sendEvent(any());
        verifyNoInteractions(h.model);
        verify(h.ctx, never()).durableExecute(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayUsesSerializedFailureAndSuccessWithoutCallingProviderAgain(boolean async)
            throws Exception {
        Harness h = new Harness(async, 1);
        when(h.model.decide(any(), anyMap()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(
                        result("provider-model", new DecisionResult.TokenUsage(7L, null, null)));

        h.run();
        assertThat(h.response().isSuccess()).isTrue();
        assertThat(h.stored).hasSize(2);
        h.events.clear();
        h.slot = 0;
        h.run();

        assertThat(h.response().isSuccess()).isTrue();
        assertThat(h.response().getRetryCount()).isEqualTo(1);
        assertThat(h.response().getResponse().getUsage().getInputTokens()).isEqualTo(7L);
        verify(h.model, times(2)).decide(any(), anyMap());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void durableInfrastructureFailurePropagatesWithoutRetryOrTerminal(
            boolean async, boolean afterCall) throws Exception {
        Harness h = new Harness(async, 3);
        IllegalStateException failure = new IllegalStateException("state persistence failed");
        when(h.model.decide(any(), anyMap())).thenReturn(result(null, null));
        if (async) {
            doAnswer(
                            invocation ->
                                    (DurableFuture<Object>)
                                            () -> {
                                                if (afterCall) {
                                                    invocation
                                                            .<DurableCallable<?>>getArgument(0)
                                                            .call();
                                                }
                                                throw failure;
                                            })
                    .when(h.ctx)
                    .durableExecuteAsync(any());
        } else {
            doAnswer(
                            invocation -> {
                                if (afterCall) {
                                    invocation.<DurableCallable<?>>getArgument(0).call();
                                }
                                throw failure;
                            })
                    .when(h.ctx)
                    .durableExecute(any());
        }

        assertThatThrownBy(h::run).isSameAs(failure);

        assertThat(h.events).isEmpty();
        verify(h.ctx, times(async ? 0 : 1)).durableExecute(any());
        verify(h.ctx, times(async ? 1 : 0)).durableExecuteAsync(any());
        verify(h.model, times(afterCall ? 1 : 0)).decide(any(), anyMap());
        verify(h.reporter, never())
                .reportExecutionFailed(
                        anyString(), anyString(), anyString(), anyMap(), any(), any());
        verify(h.reporter, never())
                .reportExecutionSucceeded(anyString(), anyString(), anyString(), anyMap());
        assertThat(h.resourceCounters).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void recordsStartedRetryWhenDurableExecutionExits(boolean async, boolean cancelled)
            throws Exception {
        Harness h = new Harness(async, 3);
        RuntimeException failure =
                cancelled
                        ? new CancellationException("cancelled")
                        : new IllegalStateException("persistence failed");
        when(h.model.decide(any(), anyMap()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        doAnswer(
                        invocation -> {
                            if (h.slot == 1) {
                                throw failure;
                            }
                            return h.execute(invocation.getArgument(0));
                        })
                .when(h.ctx)
                .durableExecute(any());
        doAnswer(
                        invocation ->
                                (DurableFuture<Object>)
                                        () -> {
                                            if (h.slot == 1) {
                                                throw failure;
                                            }
                                            return h.execute(invocation.getArgument(0));
                                        })
                .when(h.ctx)
                .durableExecuteAsync(any());

        assertThatThrownBy(h::run).isSameAs(failure);

        assertThat(h.events).isEmpty();
        verify(h.model).decide(any(), anyMap());
        verify(h.ctx, times(async ? 0 : 2)).durableExecute(any());
        verify(h.ctx, times(async ? 2 : 0)).durableExecuteAsync(any());
        assertThat(h.resourceCounters).containsOnlyKeys("retryCount", "retryWaitSec");
        assertThat(h.resourceCounters.get("retryCount").getCount()).isEqualTo(1);
        assertThat(h.resourceCounters.get("retryWaitSec").getCount()).isZero();
        verify(h.reporter)
                .reportExecutionFailed(
                        anyString(),
                        eq("llm"),
                        eq(MODEL),
                        eq(Map.of()),
                        any(),
                        eq("model_call_failed"));
        verify(h.reporter, never())
                .reportExecutionSucceeded(anyString(), anyString(), anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void interruptedBackoffPreservesAccumulatedRetryMetrics(int failedAttempt) throws Exception {
        Harness h = new Harness(false, 3);
        when(h.ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL)).thenReturn(1);
        when(h.model.decide(any(), anyMap()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        doAnswer(
                        invocation -> {
                            Object outcome = h.execute(invocation.getArgument(0));
                            if (h.slot == failedAttempt) {
                                Thread.currentThread().interrupt();
                            }
                            return outcome;
                        })
                .when(h.ctx)
                .durableExecute(any());

        assertThatThrownBy(h::run).isInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(h.events).isEmpty();
        verify(h.model, times(failedAttempt)).decide(any(), anyMap());
        assertThat(h.resourceCounters).containsOnlyKeys("retryCount", "retryWaitSec");
        assertThat(h.resourceCounters.get("retryCount").getCount()).isEqualTo(failedAttempt);
        assertThat(h.resourceCounters.get("retryWaitSec").getCount()).isEqualTo(failedAttempt - 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAndWrappedInterruptionEscapeWithoutRetry(boolean interrupted)
            throws Exception {
        Harness h = new Harness(true, 3);
        RuntimeException failure =
                interrupted
                        ? new RuntimeException(new InterruptedException("cancelled"))
                        : new CancellationException("cancelled");
        when(h.model.decide(any(), anyMap())).thenThrow(failure);

        assertThatThrownBy(h::run).isSameAs(failure);

        assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        assertThat(h.events).isEmpty();
        assertThat(h.stored).isEmpty();
        verify(h.model).decide(any(), anyMap());
        verify(h.reporter, never())
                .reportExecutionFailed(
                        anyString(), anyString(), anyString(), anyMap(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalDeliveryFailurePropagatesOnce(boolean providerFails) throws Exception {
        Harness h = new Harness(false, 0);
        if (providerFails) {
            when(h.model.decide(any(), anyMap()))
                    .thenThrow(new IllegalStateException("provider failed"));
        } else {
            when(h.model.decide(any(), anyMap())).thenReturn(result(null, null));
        }
        IllegalStateException failure = new IllegalStateException("delivery failed");
        doThrow(failure).when(h.ctx).sendEvent(any());

        assertThatThrownBy(h::run).isSameAs(failure);

        verify(h.ctx).sendEvent(any());
        verify(h.model).decide(any(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsEachReportedTokenCountIncludingZero(boolean reportedModel) throws Exception {
        Harness h = new Harness(false, 0);
        when(h.model.decide(any(), anyMap()))
                .thenReturn(
                        result(
                                reportedModel ? "provider-model" : null,
                                new DecisionResult.TokenUsage(0L, null, 9L)));

        h.run();

        Map<String, SimpleCounter> counts = reportedModel ? h.providerCounters : h.resourceCounters;
        assertThat(counts).containsOnlyKeys("promptTokens", "totalTokens");
        assertThat(counts.get("promptTokens").getCount()).isZero();
        assertThat(counts.get("totalTokens").getCount()).isEqualTo(9);
        assertThat(reportedModel ? h.resourceCounters : h.providerCounters).isEmpty();
    }

    @Test
    void reportingFailureCannotChangeSuccessfulResult() throws Exception {
        Harness h = new Harness(false, 0);
        when(h.model.decide(any(), anyMap())).thenReturn(result(null, null));
        doThrow(new IllegalStateException("reporter failed"))
                .when(h.reporter)
                .reportExecutionStarted(anyString(), anyString(), anyString(), anyMap());
        doThrow(new IllegalStateException("reporter failed"))
                .when(h.reporter)
                .reportExecutionSucceeded(anyString(), anyString(), anyString(), anyMap());

        h.run();

        assertThat(h.response().isSuccess()).isTrue();
        verify(h.model).decide(any(), anyMap());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void pythonCallsUseAsyncOnlyWhenSupported(boolean javaSetup, boolean asyncSupported)
            throws Exception {
        Harness h = new Harness(true, 0);
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pythonResource = mock(PyObject.class);
        ResourceDescriptor descriptor =
                new ResourceDescriptor(
                        "unused", Map.of("connection", "shared", "model", "provider-model"));
        when(adapter.invoke(anyString(), same(pythonResource), anyString(), any(), any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                h.mapper.writeValueAsString(result(null, null))));
        BaseDecisionModelSetup model;
        if (javaSetup) {
            PythonDecisionModelConnection connection =
                    new PythonDecisionModelConnection(adapter, pythonResource, descriptor, null);
            model =
                    new BaseDecisionModelSetup(
                            descriptor,
                            ResourceContext.fromGetResource((name, type) -> connection));
        } else {
            model = new PythonDecisionModelSetup(adapter, pythonResource, descriptor, null);
        }
        model.open();
        when(h.ctx.getResource(MODEL, ResourceType.DECISION_MODEL)).thenReturn(model);
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(Utils::supportAsync).thenReturn(asyncSupported);
            h.run();
        }

        assertThat(h.response().isSuccess()).isTrue();
        verify(h.ctx, times(asyncSupported ? 0 : 1)).durableExecute(any());
        verify(h.ctx, times(asyncSupported ? 1 : 0)).durableExecuteAsync(any());
    }

    private static DecisionResult result(String model, DecisionResult.TokenUsage usage) {
        return new DecisionResult(
                Map.of("damaged", new DecisionResult.PredicateAnswer(0.8)), model, usage, Map.of());
    }

    private static final class Harness {
        private final RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        private final ExecutionReporter reporter = (ExecutionReporter) ctx;
        private final BaseDecisionModelSetup model = mock(BaseDecisionModelSetup.class);
        private final List<Event> events = new ArrayList<>();
        private final Map<String, SimpleCounter> resourceCounters = new HashMap<>();
        private final Map<String, SimpleCounter> providerCounters = new HashMap<>();
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<byte[]> stored = new ArrayList<>();
        private int slot;
        private final DecisionRequestEvent request =
                new DecisionRequestEvent(
                        MODEL,
                        new DecisionRequest(
                                "The delivery arrived damaged",
                                Map.of(
                                        "damaged",
                                        DecisionRequest.Question.predicate("Is it damaged?"))),
                        Map.of("safety_identifier", "review-user", "timeout", 4.0));

        private Harness(boolean async, int retries) throws Exception {
            ReadableConfiguration config = mock(ReadableConfiguration.class);
            when(config.get(AgentExecutionOptions.DECISION_ASYNC)).thenReturn(async);
            when(config.get(AgentExecutionOptions.MAX_RETRIES)).thenReturn(retries);
            when(config.get(AgentExecutionOptions.RETRY_WAIT_INTERVAL)).thenReturn(0);
            when(ctx.getConfig()).thenReturn(config);
            when(ctx.getResource(MODEL, ResourceType.DECISION_MODEL)).thenReturn(model);
            when(model.supportsAsyncExecution()).thenReturn(true);
            doAnswer(invocation -> events.add(invocation.getArgument(0)))
                    .when(ctx)
                    .sendEvent(any());
            when(ctx.durableExecute(any()))
                    .thenAnswer(invocation -> execute(invocation.getArgument(0)));
            when(ctx.durableExecuteAsync(any()))
                    .thenAnswer(
                            invocation ->
                                    (DurableFuture<Object>)
                                            () -> execute(invocation.getArgument(0)));

            FlinkAgentsMetricGroup actionMetrics = mock(FlinkAgentsMetricGroup.class);
            FlinkAgentsMetricGroup resourceMetrics = mock(FlinkAgentsMetricGroup.class);
            FlinkAgentsMetricGroup providerMetrics = mock(FlinkAgentsMetricGroup.class);
            when(ctx.getActionMetricGroup()).thenReturn(actionMetrics);
            when(actionMetrics.getSubGroup("model_resource", MODEL)).thenReturn(resourceMetrics);
            when(resourceMetrics.getSubGroup("model", "provider-model"))
                    .thenReturn(providerMetrics);
            when(resourceMetrics.getCounter(anyString()))
                    .thenAnswer(
                            invocation ->
                                    resourceCounters.computeIfAbsent(
                                            invocation.getArgument(0), key -> new SimpleCounter()));
            when(providerMetrics.getCounter(anyString()))
                    .thenAnswer(
                            invocation ->
                                    providerCounters.computeIfAbsent(
                                            invocation.getArgument(0), key -> new SimpleCounter()));
        }

        private Object execute(DurableCallable<?> call) throws Exception {
            int currentSlot = slot++;
            if (currentSlot == stored.size()) {
                stored.add(mapper.writeValueAsBytes(call.call()));
            }
            return mapper.readValue(stored.get(currentSlot), call.getResultClass());
        }

        private void run() throws Exception {
            DecisionModelAction.processDecisionRequest(request, ctx);
        }

        private DecisionResponseEvent response() {
            assertThat(events).hasSize(1);
            return (DecisionResponseEvent) events.get(0);
        }
    }
}
