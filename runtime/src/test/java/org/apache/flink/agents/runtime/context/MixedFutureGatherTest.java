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
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.chat.ChatSubgraphDefinition;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.subagent.CompletedSubagentFuture;
import org.apache.flink.agents.runtime.subagent.PendingSubagentCallRegistry;
import org.apache.flink.agents.runtime.subagent.SubagentFutureImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphCallManager;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.agents.runtime.subgraph.SubgraphFuture;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;
import org.apache.flink.agents.runtime.utils.DeterministicIdAllocator;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Production mixed scheduler exercised with a real journal and continuation executor. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(15)
class MixedFutureGatherTest {
    @Test
    void mixedCallsReserveInOrderAndGraphsProgressWithoutWorkers() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger starts = new AtomicInteger();
            Callable<String> work =
                    () -> {
                        assertThat(f.persistedSize).hasValue(4);
                        starts.incrementAndGet();
                        release.await();
                        return "tool";
                    };
            var tool = f.runner.durableExecuteAsync(call("tool", String.class, work));
            var deferred = f.runner.durableExecuteAsync(call("deferred", String.class, work));
            var child =
                    AsyncFutureImpl.map(f.runner, f.internal("child"), r -> (String) r.getResult());
            var chat =
                    AsyncFutureImpl.map(
                            f.runner, f.chat(), r -> r.getResponse().getMessage().getText());
            var run = f.start(() -> f.runner.gather(List.of(tool, deferred, child, chat)).await());
            try {
                while (starts.get() < 2) {
                    Thread.sleep(1);
                }
                assertThat(f.state.getCallResults()).hasSize(4).allMatch(r -> r.isPending());
                assertThat(f.completeGraphs(false)).isEqualTo(2);
            } finally {
                release.countDown();
            }
            var results = f.finish(run);
            assertThat(results).allMatch(r -> !r.isFailure());
            assertThat(results)
                    .extracting(Outcome::getValue)
                    .containsExactly("tool", "tool", "child-answer", "chat-answer");
            assertThat(f.runner.getCurrentCallIndex()).isEqualTo(4);
            assertThat(f.run(tool::await)).isEqualTo("tool");
        }
    }

    @Test
    void partialRecoveryReplaysCompletedToolAndRestartsOnlyPendingGraph() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] snapshot;
        try (Fixture f = new Fixture()) {
            var tool =
                    f.runner.durableExecuteAsync(
                            call("tool", String.class, () -> "value-" + calls.incrementAndGet()));
            var child =
                    AsyncFutureImpl.map(f.runner, f.internal("child"), r -> (String) r.getResult());
            var run = f.start(() -> f.runner.gather(List.of(tool, child)).await());
            while (!f.state.getCallResults().get(0).isSuccess()) {
                f.step(run);
            }
            snapshot = ActionStateSerde.serialize(f.state);
            f.completeGraphs(false);
            f.finish(run);
        }
        try (Fixture f = new Fixture(ActionStateSerde.deserialize(snapshot))) {
            var tool =
                    f.runner.durableExecuteAsync(
                            call("tool", String.class, () -> "value-" + calls.incrementAndGet()));
            var child =
                    AsyncFutureImpl.map(f.runner, f.internal("child"), r -> (String) r.getResult());
            var run = f.start(() -> f.runner.gather(List.of(tool, child)).await());
            assertThat(f.completeGraphs(false)).isEqualTo(1);
            assertThat(f.finish(run))
                    .extracting(Outcome::getValue)
                    .containsExactly("value-1", "child-answer");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void completedRecoveryStartsNeitherWorkersNorGraphs() throws Exception {
        byte[] snapshot;
        AtomicInteger calls = new AtomicInteger();
        try (Fixture f = new Fixture()) {
            var run =
                    f.start(
                            () ->
                                    f.runner
                                            .gather(
                                                    List.of(
                                                            AsyncFutureImpl.map(
                                                                    f.runner,
                                                                    f.chat(),
                                                                    r ->
                                                                            r.getResponse()
                                                                                    .getMessage()
                                                                                    .getText()),
                                                            f.runner.durableExecuteAsync(
                                                                    call(
                                                                            "tool",
                                                                            String.class,
                                                                            () ->
                                                                                    "v"
                                                                                            + calls
                                                                                                    .incrementAndGet()))))
                                            .await());
            f.completeGraphs(false);
            f.finish(run);
            snapshot = ActionStateSerde.serialize(f.state);
        }
        try (Fixture f = new Fixture(ActionStateSerde.deserialize(snapshot))) {
            var results =
                    f.run(
                            () ->
                                    f.runner
                                            .gather(
                                                    List.of(
                                                            AsyncFutureImpl.map(
                                                                    f.runner,
                                                                    f.chat(),
                                                                    r ->
                                                                            r.getResponse()
                                                                                    .getMessage()
                                                                                    .getText()),
                                                            f.runner.durableExecuteAsync(
                                                                    call(
                                                                            "tool",
                                                                            String.class,
                                                                            () ->
                                                                                    "v"
                                                                                            + calls
                                                                                                    .incrementAndGet()))))
                                            .await());
            assertThat(results).extracting(Outcome::getValue).containsExactly("chat-answer", "v1");
            assertThat(f.completeGraphs(false)).isZero();
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void mappedFailedChatPersistsRawResponseAndReplaysSameFailure() throws Exception {
        byte[] snapshot;
        try (Fixture f = new Fixture()) {
            var run =
                    f.start(
                            () ->
                                    f.runner
                                            .gather(List.of(f.runner.chat("model", List.of())))
                                            .await());
            f.completeGraphs(true);
            assertThat(f.finish(run).get(0).isFailure()).isTrue();
            assertThat(f.state.getCallResults().get(0).isSuccess()).isTrue();
            snapshot = ActionStateSerde.serialize(f.state);
        }
        try (Fixture f = new Fixture(ActionStateSerde.deserialize(snapshot))) {
            var outcomes =
                    f.run(
                            () ->
                                    f.runner
                                            .gather(List.of(f.runner.chat("model", List.of())))
                                            .await());
            assertThat(outcomes.get(0).isFailure()).isTrue();
            assertThat(f.completeGraphs(false)).isZero();
        }
    }

    @Test
    void subagentFacadesUseSameGatherAndReleasePendingRegistry() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingSubagentCallRegistry registry = new PendingSubagentCallRegistry("action");
            var deferred =
                    new SubagentFutureImpl(
                            "s",
                            "remote",
                            f.runner,
                            () ->
                                    f.runner.durableExecuteAsync(
                                            call(
                                                    "remote",
                                                    SubagentResult.class,
                                                    () -> SubagentResult.ok("remote"))),
                            registry,
                            () -> false,
                            () -> {});
            var internal =
                    new SubagentFutureImpl(
                            "s",
                            "child",
                            f.runner,
                            () -> f.internal("child"),
                            registry,
                            () -> false,
                            () -> {});
            var run = f.start(() -> deferred.combine(internal).awaitAll());
            f.completeGraphs(false);
            assertThat(f.finish(run))
                    .extracting(SubagentResult::getResult)
                    .containsExactly("remote", "child-answer");
            registry.checkEmpty();
        }
    }

    @Test
    void mappingSharesIdentityAndDuplicateRejectionDoesNotReserveSlots() throws Exception {
        try (Fixture f = new Fixture()) {
            var tool = f.runner.durableExecuteAsync(call("tool", String.class, () -> "value"));
            var mapped = AsyncFutureImpl.map(f.runner, tool, v -> v);
            assertThatThrownBy(() -> f.runner.gather(List.of(tool, mapped)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(f.state.getCallResults()).isEmpty();
        }
    }

    @Test
    void ordinaryFailureIsIsolatedAndFatalFailureRemainsPending() throws Exception {
        try (Fixture f = new Fixture()) {
            var results =
                    f.run(
                            () ->
                                    f.runner
                                            .gather(
                                                    List.of(
                                                            f.runner.durableExecuteAsync(
                                                                    call(
                                                                            "bad",
                                                                            String.class,
                                                                            () -> {
                                                                                throw new IllegalArgumentException(
                                                                                        "bad");
                                                                            })),
                                                            f.runner.executeAsync(() -> "ok")))
                                            .await());
            assertThat(results.get(0).getError()).hasMessage("bad");
            assertThat(results.get(1).getValue()).isEqualTo("ok");
        }
        try (Fixture f = new Fixture()) {
            var fatal =
                    f.runner.durableExecuteAsync(
                            call(
                                    "fatal",
                                    String.class,
                                    () -> {
                                        throw new AssertionError("fatal");
                                    }));
            assertThatThrownBy(() -> f.run(() -> f.runner.gather(List.of(fatal)).await()))
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("fatal");
            assertThat(f.state.getCallResults().get(0).isPending()).isTrue();
        }
    }

    @Test
    void directRetryAfterCancelledBatchReusesReservedSlot() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger attempts = new AtomicInteger();
            var future =
                    f.runner.durableExecuteAsync(
                            call(
                                    "retry",
                                    String.class,
                                    () -> {
                                        if (attempts.incrementAndGet() == 1) {
                                            throw new CancellationException("cancel attempt");
                                        }
                                        return "ok";
                                    }));
            assertThatThrownBy(() -> f.run(() -> f.runner.gather(List.of(future)).await()))
                    .isInstanceOf(CancellationException.class);
            assertThat(f.state.getCallResults()).hasSize(1);
            assertThat(f.state.getCallResults().get(0).isPending()).isTrue();
            assertThat(f.run(future::await)).isEqualTo("ok");
            assertThat(f.state.getCallResults()).hasSize(1);
            assertThat(f.state.getCallResults().get(0).isSuccess()).isTrue();
            assertThat(f.runner.getCurrentCallIndex()).isEqualTo(1);
        }
    }

    @Test
    void graphRuntimeFailureIsNotPersistedAsChatOutcome() throws Exception {
        try (Fixture f = new Fixture()) {
            var chat = f.chat();
            var run = f.start(() -> f.runner.gather(List.of(chat)).await());
            IllegalStateException fault = new IllegalStateException("graph runtime failed");
            chat.getInvocation().completionFuture().completeExceptionally(fault);
            assertThatThrownBy(() -> f.finish(run)).isSameAs(fault);
            assertThat(f.state.getCallResults()).hasSize(1);
            assertThat(f.state.getCallResults().get(0).isPending()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schedulesInterfaceImplementationWithoutAsyncFutureBaseClass(boolean gathered)
            throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger closed = new AtomicInteger();
            SchedulableFuture<String> custom =
                    new SchedulableFuture<>() {
                        @Override
                        public RunnerContext getOwnerContext() {
                            return f.runner;
                        }

                        @Override
                        public Outcome<String> prepare(RunnerContextImpl context) {
                            return null;
                        }

                        @Override
                        public CompletableFuture<?> start() {
                            // The following durable call must already have reserved its slot.
                            assertThat(f.state.getCallResults()).hasSize(1);
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public Outcome<String> finish(TimeoutException timeout) {
                            assertThat(timeout).isNull();
                            return Outcome.success("custom");
                        }

                        @Override
                        public void cancel() {
                            throw new AssertionError("A successful call must not be cancelled");
                        }

                        @Override
                        public void close() {
                            closed.incrementAndGet();
                        }

                        @Override
                        public String await() {
                            throw new AssertionError(
                                    "gather must use the lifecycle, not call await");
                        }
                    };
            var mapped = AsyncFutureImpl.map(f.runner, custom, String::toUpperCase);
            var durable = f.runner.durableExecuteAsync(call("tool", String.class, () -> "tool"));
            if (gathered) {
                var outcomes = f.run(() -> f.runner.gather(List.of(mapped, durable)).await());
                assertThat(outcomes)
                        .extracting(Outcome::getValue)
                        .containsExactly("CUSTOM", "tool");
            } else {
                assertThat(f.run(durable::await)).isEqualTo("tool");
                assertThat(f.run(mapped::await)).isEqualTo("CUSTOM");
                assertThat(f.run(mapped::await)).isEqualTo("CUSTOM");
            }
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    void completedSubagentParticipatesDirectlyWithoutConsumingDurableSlot() throws Exception {
        try (Fixture f = new Fixture()) {
            var completed = new CompletedSubagentFuture("s", "done", SubagentResult.ok("saved"));
            var mapped =
                    AsyncFutureImpl.map(f.runner, completed, value -> (String) value.getResult());
            var durable = f.runner.durableExecuteAsync(call("tool", String.class, () -> "tool"));
            var outcomes = f.run(() -> f.runner.gather(List.of(mapped, durable)).await());
            assertThat(outcomes).extracting(Outcome::getValue).containsExactly("saved", "tool");
            assertThat(f.state.getCallResults()).hasSize(1);
            assertThat(f.run(() -> f.runner.gather(List.of(completed)).await()).get(0).getValue())
                    .isSameAs(completed.await());
            assertThat(f.state.getCallResults()).hasSize(1);
        }
    }

    @Test
    void remoteCompletionProbeDoesNotSkipFetchingAndRecordingResult() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger fetches = new AtomicInteger();
            AtomicInteger cancellations = new AtomicInteger();
            PendingSubagentCallRegistry registry = new PendingSubagentCallRegistry("action");
            var remote =
                    new SubagentFutureImpl(
                            "s",
                            "remote",
                            f.runner,
                            () ->
                                    f.runner.durableExecuteAsync(
                                            call(
                                                    "remote-await",
                                                    SubagentResult.class,
                                                    () -> {
                                                        fetches.incrementAndGet();
                                                        return SubagentResult.ok("fetched");
                                                    })),
                            registry,
                            () -> true,
                            cancellations::incrementAndGet);
            assertThat(remote.isDone()).isTrue();
            var outcomes = f.run(() -> f.runner.gather(List.of(remote)).await());
            assertThat(outcomes.get(0).getValue().getResult()).isEqualTo("fetched");
            assertThat(fetches).hasValue(1);
            assertThat(cancellations).hasValue(0);
            assertThat(f.state.getCallResults()).hasSize(1);
            assertThat(f.state.getCallResults().get(0).isSuccess()).isTrue();
            registry.checkEmpty();
            assertThat(f.run(remote::await).getResult()).isEqualTo("fetched");
            assertThat(fetches).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "fatal"})
    void completionBetweenTimeoutSweepAndCancellationKeepsActualOutcome(String outcome)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] snapshot;
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException businessFailure = new IllegalStateException("tool failed");
        AssertionError fatal = new AssertionError("fatal tool failure");
        try (Fixture f = new Fixture()) {
            ((Configuration) f.runner.getConfig())
                    .set(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT_MS, 250L);
            var tool =
                    f.runner.durableExecuteAsync(
                            call(
                                    "tool",
                                    String.class,
                                    () -> {
                                        calls.incrementAndGet();
                                        release.await();
                                        if (outcome.equals("failure")) {
                                            throw businessFailure;
                                        }
                                        if (outcome.equals("fatal")) {
                                            throw fatal;
                                        }
                                        return "tool-result";
                                    }));
            var racing =
                    completeBeforeTimeoutCancellation(
                            AsyncFutureImpl.requireSchedulable(tool, f.runner), release::countDown);
            if (outcome.equals("fatal")) {
                assertThatThrownBy(() -> f.run(() -> f.runner.gather(List.of(racing)).await()))
                        .isSameAs(fatal);
                assertThat(f.state.getCallResults().get(0).isPending()).isTrue();
                return;
            }
            var result = f.run(() -> f.runner.gather(List.of(racing)).await()).get(0);
            if (outcome.equals("failure")) {
                assertThat(result.getError()).isSameAs(businessFailure);
            } else {
                assertThat(result.getValue()).isEqualTo("tool-result");
                assertThat(result.isFailure()).isFalse();
            }
            snapshot = ActionStateSerde.serialize(f.state);
        } finally {
            release.countDown();
        }
        try (Fixture f = new Fixture(ActionStateSerde.deserialize(snapshot))) {
            var tool =
                    f.runner.durableExecuteAsync(
                            call(
                                    "tool",
                                    String.class,
                                    () -> {
                                        calls.incrementAndGet();
                                        return "unexpected retry";
                                    }));
            var replay = f.run(() -> f.runner.gather(List.of(tool)).await()).get(0);
            if (outcome.equals("failure")) {
                assertThat(replay.getError())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("tool failed");
            } else {
                assertThat(replay.getValue()).isEqualTo("tool-result");
                assertThat(replay.isFailure()).isFalse();
            }
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void graphCompletionBetweenTimeoutSweepAndCancellationIsRecorded(boolean chat)
            throws Exception {
        byte[] snapshot;
        try (Fixture f = new Fixture()) {
            ((Configuration) f.runner.getConfig())
                    .set(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT_MS, 250L);
            var graph =
                    chat
                            ? AsyncFutureImpl.map(
                                    f.runner, f.chat(), r -> r.getResponse().getMessage().getText())
                            : AsyncFutureImpl.map(
                                    f.runner, f.internal("child"), r -> (String) r.getResult());
            var racing =
                    completeBeforeTimeoutCancellation(
                            AsyncFutureImpl.requireSchedulable(graph, f.runner),
                            () -> assertThat(f.completeGraphs(false)).isEqualTo(1));
            var result = f.run(() -> f.runner.gather(List.of(racing)).await()).get(0);
            assertThat(result.isFailure()).isFalse();
            assertThat(result.getValue()).isEqualTo(chat ? "chat-answer" : "child-answer");
            assertThat(f.state.getCallResults().get(0).isSuccess()).isTrue();
            snapshot = ActionStateSerde.serialize(f.state);
        }
        try (Fixture f = new Fixture(ActionStateSerde.deserialize(snapshot))) {
            var graph =
                    chat
                            ? AsyncFutureImpl.map(
                                    f.runner, f.chat(), r -> r.getResponse().getMessage().getText())
                            : AsyncFutureImpl.map(
                                    f.runner, f.internal("child"), r -> (String) r.getResult());
            var replay = f.run(() -> f.runner.gather(List.of(graph)).await()).get(0);
            assertThat(replay.isFailure()).isFalse();
            assertThat(replay.getValue()).isEqualTo(chat ? "chat-answer" : "child-answer");
            assertThat(f.completeGraphs(false)).isZero();
        }
    }

    /** Completes the real call after the scheduler's sweep, but before cancellation. */
    @SuppressWarnings("unchecked")
    private static <T> SchedulableFuture<T> completeBeforeTimeoutCancellation(
            SchedulableFuture<T> source, Runnable complete) throws Exception {
        SchedulableFuture<T> wrapper = mock(SchedulableFuture.class, delegatesTo(source));
        AtomicReference<CompletableFuture<?>> signal = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            var started = source.start();
                            signal.set(started);
                            return started;
                        })
                .when(wrapper)
                .start();
        doAnswer(
                        invocation -> {
                            source.preventStart();
                            complete.run();
                            // Wait for exceptional completions too, without throwing their error in
                            // this hook.
                            signal.get().handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
                            return null;
                        })
                .when(wrapper)
                .preventStart();
        return wrapper;
    }

    private static <T> DurableCallable<T> call(String id, Class<T> type, Callable<T> body) {
        return new DurableCallable<>() {
            public String getId() {
                return id;
            }

            public Class<T> getResultClass() {
                return type;
            }

            public T call() throws Exception {
                return body.call();
            }
        };
    }

    private static final class Run<T> {
        T result;
        Throwable failure;
        boolean done;
        final Runnable action;

        Run(Callable<T> work) {
            action =
                    () -> {
                        try {
                            result = work.call();
                        } catch (Throwable error) {
                            failure = error;
                        }
                    };
        }

        T result() throws Exception {
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure != null) {
                throw (Exception) failure;
            }
            return result;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ContinuationActionExecutor executor = new ContinuationActionExecutor(2);
        final JavaRunnerContextImpl runner;

        final ActionState state;
        final AtomicInteger pending = new AtomicInteger();
        final AtomicInteger persistedSize = new AtomicInteger();
        int writes;
        int failPersistenceAt = -1;

        Fixture() {
            this(new ActionState(new Event("input")));
        }

        Fixture(ActionState state) {
            this.state = state;
            Thread actionThread = Thread.currentThread();
            Event input = state.getTaskEvent();
            runner =
                    new JavaRunnerContextImpl(
                            new FlinkAgentsMetricGroupImpl(
                                    UnregisteredMetricGroups
                                            .createUnregisteredOperatorMetricGroup()),
                            () -> assertThat(Thread.currentThread()).isSameAs(actionThread),
                            new AgentPlan(new HashMap<>(), new HashMap<>()),
                            null,
                            "job",
                            executor);
            ((Configuration) runner.getConfig())
                    .set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 4);
            runner.switchActionContext(
                    "action",
                    new RunnerContextImpl.MemoryContext(null, null),
                    new ArrayList<>(),
                    "key",
                    "key",
                    "observation",
                    false,
                    null);
            runner.setContinuationContext(new ContinuationContext());
            runner.setSubgraphCalls(new SubgraphCallManager());
            Map<String, DeterministicIdAllocator> ids = new HashMap<>();
            runner.setIdAllocatorSource(
                    name ->
                            ids.computeIfAbsent(
                                    name,
                                    target ->
                                            new DeterministicIdAllocator(
                                                    "key", 1, "action", input, target)));
            runner.setDurableExecutionContext(
                    new RunnerContextImpl.DurableExecutionContext(
                            "key",
                            1,
                            null,
                            input,
                            state,
                            (k, s, a, e, saved) -> {
                                if (++writes == failPersistenceAt) {
                                    throw new IllegalStateException("persistence unavailable");
                                }
                                persistedSize.set(saved.getCallResults().size());
                            }));
        }

        SubgraphFuture<ChatResponseEvent, ChatResponseEvent> chat() {
            return new SubgraphFuture<>(
                    runner,
                    () -> ChatSubgraphDefinition.invocationFor(runner),
                    new ChatRequestEvent("model", List.of(), null),
                    value -> value);
        }

        SubgraphFuture<SubagentResult, SubagentResult> internal(String id) {
            SubgraphDefinition<SubagentResult> definition =
                    new SubgraphDefinition<>() {
                        public String kind() {
                            return "subagent";
                        }

                        public List<Action> matchActions(Event event) {
                            return List.of();
                        }

                        public boolean isOutput(Event event) {
                            return event instanceof OutputEvent;
                        }

                        public SubagentResult finish(List<Event> outputs) {
                            return SubagentResult.ok(
                                    OutputEvent.fromEvent(outputs.get(0)).getOutput());
                        }

                        public Class<SubagentResult> resultClass() {
                            return SubagentResult.class;
                        }

                        public Event inputEvent(Object input) {
                            return new Event("child-input");
                        }

                        public SubgraphInvocation<SubagentResult> newInvocation(
                                RunnerContextImpl context, String name, Object key) {
                            return new SubgraphInvocation<>(name, key, this, null);
                        }

                        public boolean capturesActionFailures() {
                            return true;
                        }

                        public SubagentResult captureActionFailure(Throwable cause) {
                            return SubagentResult.error(cause.getMessage());
                        }
                    };
            return new SubgraphFuture<>(
                    runner,
                    () -> definition.newInvocation(runner, id, "key"),
                    "prompt",
                    value -> value);
        }

        int completeGraphs(boolean failedChat) {
            List<Event> events = runner.drainEventsAtActionYield(null);
            for (Event event : events) {
                SubgraphEvent graph = (SubgraphEvent) event;
                SubgraphInvocation<?> invocation =
                        runner.getSubgraphCalls().get(graph.getInvocationId());
                if (invocation.resultClass() == ChatResponseEvent.class) {
                    invocation.dispatch(
                            failedChat
                                    ? ChatResponseEvent.failed(
                                            UUID.randomUUID(), "provider unavailable", 1, 1)
                                    : ChatResponseEvent.success(
                                            UUID.randomUUID(),
                                            new ChatResult(ChatMessage.assistant("chat-answer"))));
                } else {
                    invocation.dispatch(new OutputEvent("child-answer"));
                }
            }
            return events.size();
        }

        <T> Run<T> start(Callable<T> work) {
            Run<T> run = new Run<>(work);
            run.done = executor.executeAction(runner.getContinuationContext(), run.action);
            return run;
        }

        void step(Run<?> run) throws InterruptedException {
            if (!run.done) {
                Thread.sleep(1);
                run.done = executor.executeAction(runner.getContinuationContext(), run.action);
            }
        }

        <T> T finish(Run<T> run) throws Exception {
            while (!run.done) {
                step(run);
            }
            return run.result();
        }

        <T> T run(Callable<T> work) throws Exception {
            return finish(start(work));
        }

        public void close() {

            runner.getSubgraphCalls().clear();
            executor.close();
        }
    }
}
