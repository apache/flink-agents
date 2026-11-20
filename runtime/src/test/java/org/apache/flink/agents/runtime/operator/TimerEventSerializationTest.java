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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.context.TimerMode;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
public class TimerEventSerializationTest {
    public static final class Payload {
        // Only the configured serializer preserves this field.
        private transient String value;

        public Payload() {}

        Payload(String value) {
            this.value = value;
        }
    }

    public static final class PayloadSerializer extends Serializer<Payload> {
        private static final AtomicInteger WRITES = new AtomicInteger();
        private static final AtomicInteger READS = new AtomicInteger();

        @Override
        public void write(Kryo kryo, Output output, Payload payload) {
            WRITES.incrementAndGet();
            output.writeString(payload.value);
        }

        @Override
        public Payload read(Kryo kryo, Input input, Class<? extends Payload> type) {
            READS.incrementAndGet();
            return new Payload(input.readString());
        }
    }

    public static void register(Event event, RunnerContext ctx) {
        String kind = (String) InputEvent.fromEvent(event).getInput();
        Event timerEvent = new Event("tick", Map.of("payload", new Payload("preserved")));
        if ("event".equals(kind)) {
            ctx.registerEventTimeTimer("timer", Instant.ofEpochMilli(100), timerEvent);
        } else if ("absolute".equals(kind)) {
            ctx.registerTimerAt(
                    "timer",
                    Instant.ofEpochMilli(100),
                    timerEvent,
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else {
            ctx.registerTimer(
                    "timer",
                    Duration.ofMillis(100),
                    timerEvent,
                    "current".equals(kind)
                            ? TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER
                            : TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        }
    }

    public static void tick(Event event, RunnerContext ctx) {
        Payload payload = (Payload) event.getAttributes().get("payload");
        ctx.sendEvent(new OutputEvent(payload.value));
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, String, Object> harness(
            boolean parallel) throws Exception {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        Map<String, Action> actions =
                Map.of(
                        "register", action("register", InputEvent.EVENT_TYPE),
                        "tick", action("tick", "tick"));
        var h =
                new KeyedOneInputStreamOperatorTestHarness<Long, String, Object>(
                        new ActionExecutionOperatorFactory<>(
                                new AgentPlan(actions, Map.of(), config), true),
                        value -> 0L,
                        Types.LONG);
        ((SerializerConfigImpl) h.getExecutionConfig().getSerializerConfig())
                .registerTypeWithKryoSerializer(Payload.class, PayloadSerializer.class);
        return h;
    }

    private static Action action(String method, String type) throws Exception {
        return new Action(
                method,
                new JavaFunction(
                        TimerEventSerializationTest.class,
                        method,
                        new Class<?>[] {Event.class, RunnerContext.class}),
                List.of(type));
    }

    @ParameterizedTest
    @CsvSource({
        "relative,false", "relative,true", "absolute,false", "absolute,true",
        "current,false", "current,true", "event,false", "event,true"
    })
    void usesConfiguredSerializerAcrossCheckpoint(String kind, boolean parallel) throws Exception {
        PayloadSerializer.WRITES.set(0);
        PayloadSerializer.READS.set(0);
        OperatorSubtaskState snapshot;
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(kind));
            CurrentRunTimerTest.pumpUntil(
                    h, () -> h.numProcessingTimeTimers() + h.numEventTimeTimers() == 1);
            assertThat(PayloadSerializer.WRITES.get()).isPositive();
            snapshot = h.snapshot(1, 0);
        }
        try (var h = harness(parallel)) {
            h.initializeState(snapshot);
            h.open();
            // Finish restoring the waiting run before advancing the test clock.
            CurrentRunTimerTest.pumpUntil(h, () -> h.getTaskMailbox().size() == 0);
            if ("event".equals(kind)) {
                h.processWatermark(new Watermark(100));
            } else {
                h.setProcessingTime(100);
            }
            ((ActionExecutionOperator<?, ?>) h.getOperator()).waitInFlightEventsFinished();
            assertThat(h.extractOutputValues()).containsExactly("preserved");
            assertThat(PayloadSerializer.READS.get()).isPositive();
        }
    }
}
