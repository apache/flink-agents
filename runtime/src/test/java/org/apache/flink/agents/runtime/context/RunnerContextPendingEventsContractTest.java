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
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests the per-task pending-event isolation contract. */
class RunnerContextPendingEventsContractTest {

    @Test
    void rootActionDrainsOnlyCallBootstrapsBeforeCompletion() {
        RunnerContextImpl context = newContext();
        RunnerContextImpl.MemoryContext memory = new RunnerContextImpl.MemoryContext(null, null);
        List<Event> buffer = new ArrayList<>();
        Event first = new Event("first");
        Event second = new Event("second");
        SubgraphEvent bootstrap = new SubgraphEvent("child-call", new InputEvent(1L), true);

        context.switchActionContext("action", memory, buffer, null, "key", "obs", false, null);
        context.sendEvent(first);
        context.sendEvent(bootstrap);
        context.sendEvent(second);

        assertThat(context.drainEventsAtActionYield(42L)).containsExactly(bootstrap);
        assertThat(bootstrap.getSourceTimestamp()).isEqualTo(42L);
        assertThat(context.drainEventsAtActionYield(42L)).isEmpty();
        assertThat(context.getPendingEvents()).isSameAs(buffer).containsExactly(first, second);
        assertThat(first.getSourceTimestamp()).isNull();
        assertThat(second.getSourceTimestamp()).isNull();

        assertThat(context.drainEventsAtActionFinish(100L)).containsExactly(first, second);
        assertThat(first.getSourceTimestamp()).isEqualTo(100L);
        assertThat(second.getSourceTimestamp()).isEqualTo(100L);
        context.checkNoPendingEvents();
    }

    @Test
    void childActionRetainsSameCallEventsUntilCompletion() {
        RunnerContextImpl context = newContext();
        context.setSubgraphInvocation(
                new SubgraphInvocation<>(
                        "child-call",
                        "key",
                        org.mockito.Mockito.mock(SubgraphDefinition.class),
                        null));
        Event next = new Event("next");
        SubgraphEvent nestedCall = new SubgraphEvent("nested-call", new InputEvent(1L), true);
        SubgraphEvent nestedSession =
                new SubgraphEvent("nested-session-call", new InputEvent(2L), true);
        context.sendEvent(next);
        context.sendEvent(nestedCall);
        context.sendEvent(nestedSession);
        Event forwarded = context.getPendingEvents().get(0);

        assertThat(context.drainEventsAtActionYield(null))
                .containsExactly(nestedCall, nestedSession);
        assertThat(context.drainEventsAtActionYield(null)).isEmpty();
        assertThat(context.getPendingEvents()).containsExactly(forwarded);
        assertThat(((SubgraphEvent) forwarded).getDelegate().getType()).isEqualTo(next.getType());
        assertThat(context.drainEventsAtActionFinish(null)).containsExactly(forwarded);
        context.checkNoPendingEvents();
    }

    @Test
    void bufferedEventsStayIsolatedPerTaskAcrossContextSwitches() {
        RunnerContextImpl context = newContext();
        RunnerContextImpl.MemoryContext memoryA = new RunnerContextImpl.MemoryContext(null, null);
        RunnerContextImpl.MemoryContext memoryB = new RunnerContextImpl.MemoryContext(null, null);
        List<Event> bufferA = new ArrayList<>();
        List<Event> bufferB = new ArrayList<>();
        Event eventA = new InputEvent(1L);

        context.switchActionContext(
                "action-a", memoryA, bufferA, null, "key-a", "obs-a", false, null);
        context.sendEvent(eventA);
        assertThat(context.drainEventsAtActionYield(null)).isEmpty();

        context.switchActionContext(
                "action-b", memoryB, bufferB, null, "key-b", "obs-b", false, null);
        assertThat(context.drainEventsAtActionFinish(null)).isEmpty();
        context.checkNoPendingEvents();

        context.switchActionContext(
                "action-a", memoryA, bufferA, null, "key-a", "obs-a", false, null);
        assertThat(context.drainEventsAtActionFinish(null)).containsExactly(eventA);
        context.checkNoPendingEvents();
    }

    private static RunnerContextImpl newContext() {
        return new RunnerContextImpl(
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                () -> {},
                new AgentPlan(new HashMap<>(), new HashMap<>()),
                null,
                "job");
    }
}
