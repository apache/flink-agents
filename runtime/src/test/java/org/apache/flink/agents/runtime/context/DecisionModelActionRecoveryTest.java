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
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.DecisionRequestEvent;
import org.apache.flink.agents.api.event.DecisionResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.DecisionModelAction;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the built-in action against the runtime's durable journal and recovery serializer. */
class DecisionModelActionRecoveryTest {
    private final AtomicReference<byte[]> persisted = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Queue<Thread> providerThreads = new ConcurrentLinkedQueue<>();
    private ContinuationActionExecutor executor;
    private final DecisionRequestEvent request =
            new DecisionRequestEvent(
                    "decision",
                    new DecisionRequest(
                            Map.of("receipt", true),
                            Map.of(
                                    "eligible",
                                    DecisionRequest.Question.predicate("Has a receipt?"))),
                    Map.of("timeout", 4.0, "extra_headers", Map.of("X-Trace-ID", "review-1")));
    private final DecisionResult result =
            new DecisionResult(
                    Map.of("eligible", new DecisionResult.PredicateAnswer(0.9)),
                    "reported-model",
                    new DecisionResult.TokenUsage(7L, null, null),
                    Map.of("evidence", true));

    @AfterEach
    void closeExecutor() {
        Thread.interrupted();
        if (executor != null) {
            executor.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void replaysCompletedProviderOutcomesWithoutAnotherCall(boolean async, boolean fail)
            throws Exception {
        BaseDecisionModelSetup model = model(fail);
        RunnerContextImpl first = context(new ActionState(request), model, async, false);
        runAction(request, first);
        assertThat(calls).hasValue(2);
        assertResponse(first, fail, 1);
        assertProviderThreads(async);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(restored.getCallResultCount()).isEqualTo(2);
        assertThat(((DecisionRequestEvent) restored.getTaskEvent()).getRequestParams())
                .isEqualTo(request.getRequestParams());
        RunnerContextImpl replay = context(restored, model, async, false);
        runAction(restored.getTaskEvent(), replay);

        assertThat(calls).hasValue(2);
        assertResponse(replay, fail, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumesAfterPersistedFailureWithoutRepeatingFailedProviderCall(boolean async)
            throws Exception {
        BaseDecisionModelSetup model = model(false);
        RunnerContextImpl interrupted = context(new ActionState(request), model, async, true);
        assertThatThrownBy(() -> runAction(request, interrupted))
                .hasMessage("simulated worker failure after persistence");
        assertThat(interrupted.getPendingEvents()).isEmpty();
        assertThat(calls).hasValue(1);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(restored.getCallResultCount()).isEqualTo(1);
        RunnerContextImpl replay = context(restored, model, async, false);
        runAction(restored.getTaskEvent(), replay);

        assertThat(calls).hasValue(2);
        assertResponse(replay, false, 1);
        assertProviderThreads(async);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asyncCancellationDoesNotPersistAndRecoveryInvokesProviderAgain(boolean interruption)
            throws Exception {
        BaseDecisionModelSetup model = mock(BaseDecisionModelSetup.class);
        when(model.supportsAsyncExecution()).thenReturn(true);
        RuntimeException cancellation =
                interruption
                        ? new RuntimeException(new InterruptedException("cancelled"))
                        : new CancellationException("cancelled");
        when(model.decide(any(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            assertThat(invocation.<Map<String, Object>>getArgument(1))
                                    .isEqualTo(request.getRequestParams());
                            providerThreads.add(Thread.currentThread());
                            if (calls.incrementAndGet() == 1) {
                                throw cancellation;
                            }
                            return result;
                        });
        ActionState state = new ActionState(request);
        RunnerContextImpl interrupted = context(state, model, true, false);

        assertThatThrownBy(() -> runAction(request, interrupted)).isSameAs(cancellation);
        assertThat(Thread.interrupted()).isEqualTo(interruption);
        assertThat(calls).hasValue(1);
        assertThat(interrupted.getPendingEvents()).isEmpty();
        assertThat(state.getCallResultCount()).isZero();
        assertThat(persisted.get()).isNull();
        assertProviderThreads(true);

        ActionState restored = ActionStateSerde.deserialize(ActionStateSerde.serialize(state));
        RunnerContextImpl replay = context(restored, model, true, false);
        runAction(restored.getTaskEvent(), replay);

        assertThat(calls).hasValue(2);
        assertThat(restored.getCallResultCount()).isEqualTo(1);
        assertResponse(replay, false, 0);
        assertProviderThreads(true);
    }

    private BaseDecisionModelSetup model(boolean alwaysFail) {
        BaseDecisionModelSetup model = mock(BaseDecisionModelSetup.class);
        when(model.supportsAsyncExecution()).thenReturn(true);
        when(model.decide(any(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            assertThat(invocation.<Map<String, Object>>getArgument(1))
                                    .isEqualTo(request.getRequestParams());
                            providerThreads.add(Thread.currentThread());
                            if (calls.incrementAndGet() == 1 || alwaysFail) {
                                throw new IllegalArgumentException("provider unavailable");
                            }
                            return result;
                        });
        return model;
    }

    private RunnerContextImpl context(
            ActionState state,
            BaseDecisionModelSetup model,
            boolean async,
            boolean failAfterPersist)
            throws Exception {
        AgentPlan plan = new AgentPlan(Map.of());
        plan.getConfig().set(AgentExecutionOptions.DECISION_ASYNC, async);
        plan.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
        plan.getConfig().set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, 0);
        ResourceCache resources = mock(ResourceCache.class);
        when(resources.getResource("decision", ResourceType.DECISION_MODEL)).thenReturn(model);
        FlinkAgentsMetricGroupImpl metrics =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        RunnerContextImpl context;
        if (async) {
            assumeTrue(Runtime.version().feature() >= 21, "async recovery needs JDK 21+");
            assertThat(ContinuationActionExecutor.isContinuationSupported()).isTrue();
            if (executor == null) {
                executor = new ContinuationActionExecutor(1);
            }
            JavaRunnerContextImpl asyncContext =
                    new JavaRunnerContextImpl(
                            metrics, () -> {}, plan, resources, "test-job", executor);
            asyncContext.setContinuationContext(new ContinuationContext());
            context = asyncContext;
        } else {
            context = new RunnerContextImpl(metrics, () -> {}, plan, resources, "test-job");
        }
        context.actionName = "decision_model_action";
        context.durableExecutionContext =
                new RunnerContextImpl.DurableExecutionContext(
                        "test-key",
                        1L,
                        DecisionModelAction.getDecisionModelAction(),
                        state.getTaskEvent(),
                        state,
                        (key, sequence, action, event, current) -> {
                            persisted.set(ActionStateSerde.serialize(current));
                            if (failAfterPersist) {
                                throw new IllegalStateException(
                                        "simulated worker failure after persistence");
                            }
                        });
        return context;
    }

    private void runAction(Event event, RunnerContextImpl context) throws Exception {
        if (!(context instanceof JavaRunnerContextImpl)) {
            DecisionModelAction.processDecisionRequest(event, context);
            return;
        }
        JavaRunnerContextImpl asyncContext = (JavaRunnerContextImpl) context;
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable action =
                () -> {
                    try {
                        DecisionModelAction.processDecisionRequest(event, context);
                    } catch (Exception error) {
                        failure.set(error);
                    }
                };
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!executor.executeAction(asyncContext.getContinuationContext(), action)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.yield();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private void assertProviderThreads(boolean async) {
        assertThat(providerThreads).hasSize(calls.get());
        if (async) {
            assertThat(providerThreads).doesNotContain(Thread.currentThread());
        } else {
            assertThat(providerThreads).containsOnly(Thread.currentThread());
        }
    }

    private void assertResponse(RunnerContextImpl context, boolean failed, int retryCount) {
        assertThat(context.getPendingEvents()).hasSize(1);
        DecisionResponseEvent response =
                DecisionResponseEvent.fromEvent(context.getPendingEvents().get(0));
        assertThat(response.getRequestId()).isEqualTo(request.getId());
        assertThat(response.getRetryCount()).isEqualTo(retryCount);
        assertThat(response.getTotalRetryWaitSec()).isZero();
        assertThat(response.isFailed()).isEqualTo(failed);
        if (failed) {
            assertThat(response.getError()).contains("provider unavailable");
        } else {
            assertThat(response.getResponse()).usingRecursiveComparison().isEqualTo(result);
        }
    }
}
