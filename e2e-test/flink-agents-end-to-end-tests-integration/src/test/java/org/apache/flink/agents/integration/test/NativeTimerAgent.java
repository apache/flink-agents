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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.context.TimerMode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises timer replacement, cancellation, payload snapshots and periodic registration. */
public class NativeTimerAgent extends Agent {
    @Action(InputEvent.EVENT_TYPE)
    public static void input(Event event, RunnerContext ctx) throws Exception {
        long key = ((Number) InputEvent.fromEvent(event).getInput()).longValue();
        ctx.getShortTermMemory().set("key", key);
        ctx.getShortTermMemory().set("ticks", 0L);
        ctx.getSensoryMemory().set("origin", true);
        ctx.registerTimer(
                "watch",
                Duration.ofMillis(50),
                tickEvent(key, -2),
                TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        Event tick = tickEvent(key, 1);
        ctx.registerTimer(
                "watch", Duration.ofMillis(100), tick, TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        tick.setAttachment("payload", new byte[] {99});
        ctx.registerTimer(
                "cancelled",
                Duration.ofMillis(50),
                tickEvent(key, -1),
                TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        ctx.cancelTimer("cancelled");
        ctx.cancelTimer("missing");
    }

    @Action("tick")
    public static void tick(Event event, RunnerContext ctx) throws Exception {
        long key = ((Number) event.getAttributes().get("key")).longValue();
        int step = ((Number) event.getAttributes().get("step")).intValue();
        assertThat(event.hasSourceTimestamp()).isFalse();
        assertThat(ctx.getSensoryMemory().isExist("origin")).isFalse();
        assertThat(ctx.getShortTermMemory().get("key").getValue()).isEqualTo(key);
        assertThat((byte[]) event.getAttachment("payload")).containsExactly((byte) key);
        long previous = ((Number) ctx.getShortTermMemory().get("ticks").getValue()).longValue();
        assertThat((long) step).isEqualTo(previous + 1);
        ctx.getShortTermMemory().set("ticks", (long) step);
        ctx.sendEvent(new OutputEvent(key + ":tick:" + step));
        if (step < 3) {
            ctx.registerTimer(
                    "watch",
                    Duration.ofMillis(50),
                    tickEvent(key, step + 1),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else {
            ctx.registerTimerAt(
                    "finish",
                    Instant.now().plusMillis(100),
                    new Event("finish", Map.of("key", key)),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        }
    }

    @Action("finish")
    public static void finish(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getAttributes().get("key") + ":done"));
    }

    private static Event tickEvent(long key, int step) {
        Event event = new Event("tick", Map.of("key", key, "step", step));
        event.setAttachment("payload", new byte[] {(byte) key});
        event.setSourceTimestamp(123L);
        return event;
    }
}
