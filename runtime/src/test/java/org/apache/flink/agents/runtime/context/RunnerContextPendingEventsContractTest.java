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
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests the per-task pending-event isolation contract. */
class RunnerContextPendingEventsContractTest {

    @Test
    void emittedEventsDrainAndBufferIsClearBeforeTaskSwitch() {
        RunnerContextImpl context = newContext();
        RunnerContextImpl.MemoryContext memoryA = new RunnerContextImpl.MemoryContext(null, null);
        RunnerContextImpl.MemoryContext memoryB = new RunnerContextImpl.MemoryContext(null, null);
        List<Event> bufferA = new ArrayList<>();
        List<Event> bufferB = new ArrayList<>();
        Event eventA = new InputEvent(1L);

        context.switchActionContext("action-a", memoryA, bufferA, "key-a", "obs-a", false, null);
        context.sendEvent(eventA);
        assertThat(context.drainEvents(null)).containsExactly(eventA);
        context.checkNoPendingEvents();

        context.switchActionContext("action-b", memoryB, bufferB, "key-b", "obs-b", false, null);
        assertThat(context.drainEvents(null)).isEmpty();
    }

    @Test
    void bufferedEventsStayIsolatedPerTaskAcrossContextSwitches() {
        RunnerContextImpl context = newContext();
        RunnerContextImpl.MemoryContext memoryA = new RunnerContextImpl.MemoryContext(null, null);
        RunnerContextImpl.MemoryContext memoryB = new RunnerContextImpl.MemoryContext(null, null);
        List<Event> bufferA = new ArrayList<>();
        List<Event> bufferB = new ArrayList<>();
        Event eventA = new InputEvent(1L);

        context.switchActionContext("action-a", memoryA, bufferA, "key-a", "obs-a", false, null);
        context.sendEvent(eventA);

        // Switching to another action task now exposes that task's own (empty) buffer: action-a's
        // event stays isolated in bufferA and cannot contaminate action-b, even though action-a
        // yielded with an undrained buffer.
        context.switchActionContext("action-b", memoryB, bufferB, "key-b", "obs-b", false, null);
        assertThat(context.drainEvents(null)).isEmpty();

        // Switching back to action-a still sees its buffered event.
        context.switchActionContext("action-a", memoryA, bufferA, "key-a", "obs-a", false, null);
        assertThat(context.drainEvents(null)).containsExactly(eventA);
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
