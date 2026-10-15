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

package org.apache.flink.agents.runtime.subgraph;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests graph lifecycle independently of chat, agents, continuations and the operator scheduler.
 */
class SubgraphInvocationTest {
    private final Action first = mock(Action.class);
    private final Action second = mock(Action.class);
    private final SubgraphDefinition<List<Event>> definition =
            new SubgraphDefinition<>() {
                @Override
                public List<Action> matchActions(Event event) {
                    return "fanout".equals(event.getType())
                            ? List.of(first, second)
                            : List.of(first);
                }

                @Override
                public boolean isOutput(Event event) {
                    return "result".equals(event.getType());
                }

                @Override
                public List<Event> finish(List<Event> outputs) {
                    return outputs;
                }

                @Override
                public boolean capturesActionFailures() {
                    return true;
                }
            };

    @Test
    void completesOnlyAfterAllProducersAndSuccessorsRetire() throws Exception {
        SubgraphInvocation<List<Event>> call = call("call", "key");
        Event result = new Event("result");
        assertThat(call.isDone()).isFalse();
        assertThat(call.dispatch(new Event("fanout"))).containsExactly(first, second);
        assertThat(call.dispatch(result)).isEmpty();
        assertThat(call.isDone()).isFalse();
        // First producer's ordinary output starts a successor before the producer retires.
        assertThat(call.dispatch(new Event("next"))).containsExactly(first);
        call.completeAction();
        call.completeAction();
        assertThat(call.isDone()).isFalse();
        call.completeAction();
        assertThat(call.awaitResult()).containsExactly(result);
        assertThatThrownBy(call::completeAction).hasMessageContaining("Unbalanced");
        assertThatThrownBy(() -> call.dispatch(new Event("next")))
                .hasMessageContaining("after subgraph completion");
    }

    @Test
    void emptyGraphCanReturnAnEmptyResult() throws Exception {
        SubgraphDefinition<List<Event>> empty =
                new SubgraphDefinition<>() {
                    @Override
                    public List<Action> matchActions(Event event) {
                        return List.of();
                    }

                    @Override
                    public boolean isOutput(Event event) {
                        return false;
                    }

                    @Override
                    public List<Event> finish(List<Event> outputs) {
                        return outputs;
                    }
                };
        SubgraphInvocation<List<Event>> call =
                new SubgraphInvocation<>("empty", "key", empty, memory(), null);
        assertThat(call.dispatch(new Event("start"))).isEmpty();
        assertThat(call.awaitResult()).isEmpty();
    }

    @Test
    void failedActionOutcomeSurvivesActionStateRoundTrip() {
        SubgraphEvent bootstrap = new SubgraphEvent("call", new Event("start"), true);
        SubgraphEvent failure =
                new SubgraphEvent(
                        "call",
                        new SubgraphFailureEvent(new IllegalArgumentException("rejected")),
                        false);
        ActionState state = new ActionState(bootstrap);
        state.addEvent(failure);
        state.markCompleted();
        ActionState restored = ActionStateSerde.deserialize(ActionStateSerde.serialize(state));
        SubgraphEvent restoredBootstrap = (SubgraphEvent) restored.getTaskEvent();
        SubgraphEvent restoredFailure = (SubgraphEvent) restored.getOutputEvents().get(0);
        assertThat(restoredBootstrap.isBootstrap()).isTrue();
        assertThat(restoredFailure.isBootstrap()).isFalse();
        assertThat(restoredFailure.getInvocationId()).isEqualTo("call");
        assertThat(restoredBootstrap.getDelegate().getId())
                .isEqualTo(bootstrap.getDelegate().getId());
        assertThat(restoredFailure.getDelegate().getId()).isEqualTo(failure.getDelegate().getId());

        SubgraphInvocation<List<Event>> call = call("call", "key");
        call.dispatch(restoredBootstrap.getDelegate());
        call.dispatch(restoredFailure.getDelegate());
        call.completeAction();
        assertThatThrownBy(call::awaitResult)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IllegalArgumentException")
                .hasMessageContaining("rejected");
        // Outputs of an already scheduled sibling must not leak or restart the failed graph.
        assertThat(call.dispatch(new Event("result"))).isEmpty();
    }

    @Test
    void doesNotCaptureCancellationOrWrappedErrors() {
        SubgraphInvocation<List<Event>> call = call("call", "key");
        assertThat(call.capturesActionFailure(new IllegalArgumentException("business"))).isTrue();
        assertThat(call.capturesActionFailure(new CancellationException())).isFalse();
        assertThat(call.capturesActionFailure(new RuntimeException(new InterruptedException())))
                .isFalse();
        assertThat(call.capturesActionFailure(new AssertionError())).isFalse();
        assertThat(call.capturesActionFailure(new RuntimeException(new OutOfMemoryError())))
                .isFalse();
    }

    @Test
    void failureWaitsForScheduledSiblingsSoRootReplayCannotLoseItsCaller() {
        SubgraphInvocation<List<Event>> call = call("call", "key");
        call.dispatch(new Event("fanout"));
        call.dispatch(new SubgraphFailureEvent(new IllegalArgumentException("rejected")));
        call.completeAction();
        assertThat(call.isDone()).isFalse();
        // Suppress both late outputs and new successors, but retire the already scheduled sibling.
        assertThat(call.dispatch(new Event("result"))).isEmpty();
        assertThat(call.dispatch(new Event("next"))).isEmpty();
        call.completeAction();
        assertThatThrownBy(call::awaitResult)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void registryIsolatesNestedCallsAndCleansUpOnlyTheFinishedRecord() {
        SubgraphCallManager calls = new SubgraphCallManager();
        SubgraphInvocation<List<Event>> outer = call("outer", "one");
        SubgraphInvocation<List<Event>> inner = call("inner", "one");
        SubgraphInvocation<List<Event>> other = call("other", "two");
        calls.register(outer);
        calls.register(inner);
        calls.register(other);
        assertThatThrownBy(() -> calls.register(call("inner", "one")))
                .hasMessageContaining("Duplicate");
        assertThat(calls.get("inner")).isSameAs(inner);
        calls.finishRecord("one");
        assertThat(outer.isDone()).isTrue();
        assertThatThrownBy(inner::awaitResult).isInstanceOf(CancellationException.class);
        assertThat(calls.find("outer")).isNull();
        assertThat(calls.get("other")).isSameAs(other);
        assertThat(other.isDone()).isFalse();
        calls.clear();
        assertThat(other.isDone()).isTrue();
        assertThatThrownBy(() -> calls.get("other")).hasMessageContaining("Missing");
    }

    private SubgraphInvocation<List<Event>> call(String id, String key) {
        return new SubgraphInvocation<>(id, key, definition, memory(), null);
    }

    private static RunnerContextImpl.MemoryContext memory() {
        return new RunnerContextImpl.MemoryContext(null, null);
    }
}
