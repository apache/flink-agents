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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.chat.ChatCallEvent;
import org.apache.flink.agents.runtime.chat.ChatCallManager;
import org.apache.flink.agents.runtime.chat.ChatCallOwner;
import org.apache.flink.agents.runtime.chat.ChatContext;
import org.apache.flink.agents.runtime.chat.ChatInvocation;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Exercises the real continuation/worker boundary, not an inline mock of executeAsync. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(10)
class ChatDurableFutureTest {
    private final ChatCallManager manager = new ChatCallManager();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsOnWorkerAndRecordsOnlyOneTerminalResponse(boolean failed) throws Exception {
        InspectingExecutor executor = new InspectingExecutor();
        try {
            ActionState state = new ActionState(new Event("input"));
            AtomicInteger writes = new AtomicInteger();
            JavaRunnerContextImpl context =
                    context(executor, state, (k, s, a, e, saved) -> writes.incrementAndGet());
            DurableFuture<ChatMessage> handle = context.chat("model", List.of());
            Event ordinary = new Event("ordinary");
            context.sendEvent(ordinary);
            Runnable action = () -> awaitTwice(handle, failed);

            assertThat(executor.executeAction(context.getContinuationContext(), action)).isFalse();
            assertThat(executor.workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.worker.get()).isNotSameAs(Thread.currentThread());
            assertThat(state.getCallResults()).isEmpty();
            ChatInvocation call = dispatchedCall(context);
            assertThat(context.getPendingEvents()).containsExactly(ordinary);
            call.complete(
                    failed
                            ? ChatResponseEvent.failed(
                                    call.getRequestId(), "provider refused", 1, 1)
                            : ChatResponseEvent.success(
                                    call.getRequestId(), ChatMessage.assistant("answer")));
            finish(executor, context, action);

            assertThat(executor.submissions).isEqualTo(1);
            assertThat(writes).hasValue(1);
            assertThat(state.getCallResults()).hasSize(1);
            // A FAILED response is still a recorded response, not a wait/execution exception.
            assertThat(state.getCallResults().get(0).isSuccess()).isTrue();
            assertThat(context.getDurableExecutionContext().getCurrentCallIndex()).isEqualTo(1);
            assertThatThrownBy(() -> manager.get(call.getId()))
                    .hasMessageContaining("Missing chat call");

            JavaRunnerContextImpl restored =
                    context(
                            executor,
                            ActionStateSerde.deserialize(ActionStateSerde.serialize(state)),
                            (k, s, a, e, saved) -> {
                                throw new AssertionError("Replay must not persist again");
                            });
            DurableFuture<ChatMessage> replay = restored.chat("model", List.of());
            assertThat(
                            executor.executeAction(
                                    restored.getContinuationContext(),
                                    () -> awaitTwice(replay, failed)))
                    .isTrue();
            assertThat(restored.getPendingEvents()).isEmpty();
            assertThat(executor.submissions).isEqualTo(1);
            assertThat(restored.getDurableExecutionContext().getCurrentCallIndex()).isEqualTo(1);
        } finally {
            executor.close();
        }
    }

    @Test
    void interruptedWaitPropagatesWithoutRecordingAChatFailure() throws Exception {
        InspectingExecutor executor = new InspectingExecutor();
        try {
            ActionState state = new ActionState(new Event("input"));
            JavaRunnerContextImpl context =
                    context(
                            executor,
                            state,
                            (k, s, a, e, saved) -> {
                                throw new AssertionError("Interrupted wait must not be persisted");
                            });
            AtomicReference<Exception> error = new AtomicReference<>();
            Runnable action = captureFailure(context.chat("model", List.of()), error);
            assertThat(executor.executeAction(context.getContinuationContext(), action)).isFalse();
            assertThat(executor.workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            ChatInvocation call = dispatchedCall(context);
            executor.worker.get().interrupt();
            finish(executor, context, action);

            assertThat(error.get()).isInstanceOf(InterruptedException.class);
            assertThat(call.isDone()).isFalse();
            assertThat(state.getCallResults()).isEmpty();
            assertThat(context.getDurableExecutionContext().getCurrentCallIndex()).isZero();
        } finally {
            executor.close();
        }
    }

    @Test
    void persistenceFailureAfterWorkerWaitIsNotConvertedToAChatFailure() throws Exception {
        InspectingExecutor executor = new InspectingExecutor();
        try {
            IllegalStateException failure = new IllegalStateException("store unavailable");
            JavaRunnerContextImpl context =
                    context(
                            executor,
                            new ActionState(new Event("input")),
                            (k, s, a, e, saved) -> {
                                throw failure;
                            });
            AtomicReference<Exception> error = new AtomicReference<>();
            Runnable action = captureFailure(context.chat("model", List.of()), error);
            assertThat(executor.executeAction(context.getContinuationContext(), action)).isFalse();
            ChatInvocation call = dispatchedCall(context);
            call.complete(
                    ChatResponseEvent.success(
                            call.getRequestId(), ChatMessage.assistant("answer")));
            finish(executor, context, action);
            assertThat(error.get()).isSameAs(failure);
            assertThat(call.awaitResponse().isSuccess()).isTrue();
        } finally {
            executor.close();
        }
    }

    private static void awaitTwice(DurableFuture<ChatMessage> handle, boolean failed) {
        if (failed) {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(handle::await)
                        .isInstanceOf(ChatResponseEvent.ChatResponseException.class)
                        .hasMessage("provider refused");
            }
        } else {
            try {
                ChatMessage response = handle.await();
                assertThat(response.getText()).isEqualTo("answer");
                assertThat(handle.await()).isSameAs(response);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private static Runnable captureFailure(
            DurableFuture<ChatMessage> handle, AtomicReference<Exception> error) {
        return () -> {
            try {
                handle.await();
            } catch (Exception e) {
                error.set(e);
            }
        };
    }

    private ChatInvocation dispatchedCall(JavaRunnerContextImpl context) {
        List<Event> events = context.drainEventsAtActionYield(null);
        assertThat(events).singleElement().isInstanceOf(ChatCallEvent.class);
        return manager.get(((ChatCallEvent) events.get(0)).getCallId());
    }

    private static void finish(
            InspectingExecutor executor, JavaRunnerContextImpl context, Runnable action)
            throws Exception {
        while (!executor.executeAction(context.getContinuationContext(), action)) {
            Thread.sleep(1);
        }
    }

    private JavaRunnerContextImpl context(
            InspectingExecutor executor, ActionState state, ActionStatePersister persister) {
        Thread actionThread = Thread.currentThread();
        Event input = new Event("input");
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> assertThat(Thread.currentThread()).isSameAs(actionThread),
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "job",
                        executor);
        context.switchActionContext(
                "action",
                new RunnerContextImpl.MemoryContext(null, null),
                new ArrayList<>(),
                "key",
                "observation",
                false,
                null);
        context.setContinuationContext(new ContinuationContext());
        context.setChatContext(
                new ChatContext(manager, new ChatCallOwner("key", 1, "action", input), null));
        context.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key", 1, mock(Action.class), input, state, persister));
        return context;
    }

    private static final class InspectingExecutor extends ContinuationActionExecutor {
        private final CountDownLatch workerStarted = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private int submissions;

        private InspectingExecutor() {
            super(1);
        }

        @Override
        public <T> T executeAsync(ContinuationContext context, Supplier<T> supplier)
                throws Exception {
            submissions++;
            return super.executeAsync(
                    context,
                    () -> {
                        worker.set(Thread.currentThread());
                        workerStarted.countDown();
                        return supplier.get();
                    });
        }
    }
}
