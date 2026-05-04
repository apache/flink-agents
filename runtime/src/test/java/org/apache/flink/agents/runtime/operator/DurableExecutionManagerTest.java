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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contract tests for {@link DurableExecutionManager}. */
class DurableExecutionManagerTest {

    @Test
    void noStoreModeMakesAllMaybeOperationsNoOp() throws Exception {
        DurableExecutionManager dem = new DurableExecutionManager(null);
        // No ACTION_STATE_STORE_BACKEND set → no default store should be created.
        dem.maybeInitActionStateStore(new AgentConfiguration());

        assertThat(dem.hasDurableStore()).isFalse();
        assertThat(dem.getActionStateStore()).isNull();

        Action action = TestActions.noopAction();
        Event event = new InputEvent(0L);

        // Every maybe* method must be a silent no-op.
        assertThat(dem.maybeGetActionState("k", 0L, action, event)).isNull();
        dem.maybeInitActionState("k", 0L, action, event);
        dem.maybePruneState("k", 0L);
        dem.notifyCheckpointComplete(1L);
        dem.snapshotRecoveryMarker();
        dem.close();
    }

    @Test
    void withInjectedStorePersistsTaskResult() throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        DurableExecutionManager dem = new DurableExecutionManager(store);

        assertThat(dem.hasDurableStore()).isTrue();
        assertThat(dem.getActionStateStore()).isSameAs(store);

        Action action = TestActions.noopAction();
        Event event = new InputEvent(42L);
        String key = "key-1";
        long seq = 0L;

        // First call seeds an initial ActionState in the store.
        dem.maybeInitActionState(key, seq, action, event);
        assertThat(store.getKeyedActionStates()).containsKey(key);
        assertThat(dem.maybeGetActionState(key, seq, action, event)).isNotNull();

        // Build a finished task result with one output event; verify persist folds it into state.
        Event outEvent = new OutputEvent(99L);
        RunnerContextImpl context = mock(RunnerContextImpl.class);
        when(context.getSensoryMemoryUpdates()).thenReturn(List.of());
        when(context.getShortTermMemoryUpdates()).thenReturn(List.of());

        ActionTask.ActionTaskResult finishedResult = mock(ActionTask.ActionTaskResult.class);
        when(finishedResult.isFinished()).thenReturn(true);
        when(finishedResult.getOutputEvents()).thenReturn(List.of(outEvent));

        dem.maybePersistTaskResult(key, seq, action, event, context, finishedResult);

        ActionState persisted = dem.maybeGetActionState(key, seq, action, event);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getOutputEvents()).contains(outEvent);
        assertThat(persisted.isCompleted()).isTrue();
        verify(context).clearDurableExecutionContext();

        dem.close();
    }
}
