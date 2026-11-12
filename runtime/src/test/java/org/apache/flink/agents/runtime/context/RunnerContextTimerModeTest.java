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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.TimerMode;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerContextTimerModeTest {
    private final TypeSerializer<Event> eventSerializer =
            TypeInformation.of(Event.class).createSerializer(new SerializerConfigImpl());

    @ParameterizedTest
    @CsvSource({
        "false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true"
    })
    void rejectsAttachmentsWithoutRecordingAnOperation(
            boolean absolute, boolean reference, boolean bridge) throws Exception {
        PythonRunnerContextImpl context = newContext();
        Event event = new Event("timeout");
        Object attachment =
                reference
                        ? MemoryRef.create(MemoryObject.MemoryType.SENSORY, "payload")
                        : new byte[] {1, 2, 3};
        event.setAttachment("payload", attachment);
        String json = new ObjectMapper().writeValueAsString(event);
        TimerMode mode = TimerMode.TRIGGER_AFTER_CURRENT_RUN;

        assertThatThrownBy(
                        () -> {
                            if (bridge) {
                                if (absolute) {
                                    context.registerTimerAtJson("watch", 123L, json, mode.name());
                                } else {
                                    context.registerTimerJson("watch", 123L, json, mode.name());
                                }
                            } else if (absolute) {
                                context.registerTimerAt("watch", Instant.EPOCH, event, mode);
                            } else {
                                context.registerTimer("watch", Duration.ZERO, event, mode);
                            }
                        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Timer Events that start a new run cannot carry attachments");
        assertThat(context.getTimerOperations()).isEmpty();
        assertThat(event.getAttachment("payload")).isSameAs(attachment);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNullModeWithoutRecordingAnOperation(boolean absolute) {
        RunnerContextImpl context = newContext();
        Event event = new Event("timeout");
        assertThatThrownBy(
                        () -> {
                            if (absolute) {
                                context.registerTimerAt("watch", Instant.EPOCH, event, null);
                            } else {
                                context.registerTimer("watch", Duration.ZERO, event, null);
                            }
                        })
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Timer mode");
        assertThat(context.getTimerOperations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bridgeResolvesTheExplicitMode(boolean absolute) throws Exception {
        PythonRunnerContextImpl context = newContext();
        String eventJson = new ObjectMapper().writeValueAsString(new Event("timeout"));
        if (absolute) {
            context.registerTimerAtJson(
                    "watch", 123L, eventJson, TimerMode.TRIGGER_AFTER_CURRENT_RUN.name());
        } else {
            context.registerTimerJson(
                    "watch", 123L, eventJson, TimerMode.TRIGGER_AFTER_CURRENT_RUN.name());
        }
        assertThat(context.getTimerOperations()).hasSize(1);
        assertThat(context.getTimerOperations().get(0).getTime()).isEqualTo(123L);
        assertThat(context.getTimerOperations().get(0).isRelative()).isEqualTo(!absolute);
        assertThat(context.getTimerOperations().get(0).restoreEvent(eventSerializer).getType())
                .isEqualTo("timeout");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bridgeRejectsUnknownModesWithoutRecordingAnOperation(boolean absolute) throws Exception {
        PythonRunnerContextImpl context = newContext();
        String eventJson = new ObjectMapper().writeValueAsString(new Event("timeout"));
        assertThatThrownBy(
                        () -> {
                            if (absolute) {
                                context.registerTimerAtJson(
                                        "watch", 123L, eventJson, "unsupported");
                            } else {
                                context.registerTimerJson("watch", 123L, eventJson, "unsupported");
                            }
                        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThat(context.getTimerOperations()).isEmpty();
    }

    private PythonRunnerContextImpl newContext() {
        return new PythonRunnerContextImpl(
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                () -> {},
                new AgentPlan(new HashMap<>(), new HashMap<>()),
                null,
                "job",
                eventSerializer);
    }
}
