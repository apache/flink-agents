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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests that emitted Events cannot carry framework-owned lineage. */
class RunnerContextLineageTest {

    @Test
    void freshEventIsAccepted() {
        RunnerContextImpl context = newContext();
        Event event = new Event("result");

        context.sendEvent(event);

        assertThat(context.getPendingEvents()).containsExactly(event);
    }

    @Test
    void presetUpstreamEventIdIsRejected() {
        RunnerContextImpl context = newContext();
        Event event = new Event("result");
        event.setUpstreamEventId(UUID.randomUUID());

        assertThatThrownBy(() -> context.sendEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstreamEventId=")
                .hasMessageNotContaining("upstreamActionName=")
                .hasMessageContaining("attributes");
        assertThat(context.getPendingEvents()).isEmpty();
    }

    @Test
    void presetUpstreamActionNameIsRejected() {
        RunnerContextImpl context = newContext();
        Event event = new Event("result");
        event.setUpstreamActionName("user_action");

        assertThatThrownBy(() -> context.sendEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstreamActionName=user_action")
                .hasMessageNotContaining("upstreamEventId=");
        assertThat(context.getPendingEvents()).isEmpty();
    }

    @Test
    void typedReconstructionOfAnEventFromAnotherActionIsRejected() {
        RunnerContextImpl context = newContext();
        Event received = new Event("generic", new HashMap<>());
        received.setUpstreamEventId(UUID.randomUUID());
        received.setUpstreamActionName("upstream_action");
        received.setAttr("input", 1L);

        Event reconstructed = InputEvent.fromEvent(received);

        assertThat(reconstructed.getUpstreamEventId()).isEqualTo(received.getUpstreamEventId());
        assertThatThrownBy(() -> context.sendEvent(reconstructed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new Event");
    }

    @Test
    void lineageArrivingThroughJsonIsRejected() throws Exception {
        // The Python bridge deserializes the event with Event.fromJson before sendEvent.
        RunnerContextImpl context = newContext();
        Event event =
                Event.fromJson(
                        "{\"type\":\"result\",\"attributes\":{},\"upstreamEventId\":\""
                                + UUID.randomUUID()
                                + "\",\"upstreamActionName\":\"python_action\"}");

        assertThatThrownBy(() -> context.sendEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("python_action");
    }

    private static RunnerContextImpl newContext() {
        RunnerContextImpl context =
                new RunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "job");
        List<Event> buffer = new ArrayList<>();
        context.switchActionContext(
                "action",
                new RunnerContextImpl.MemoryContext(null, null),
                buffer,
                "key",
                "obs",
                false,
                null);
        return context;
    }
}
