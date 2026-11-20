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

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises event-time registration, replacement, cancellation and due-timer chaining. */
public class EventTimeTimerAgent extends Agent {
    @Action(InputEvent.EVENT_TYPE)
    public static void input(Event event, RunnerContext ctx) throws Exception {
        long key = ((Number) InputEvent.fromEvent(event).getInput()).longValue();
        ctx.getSensoryMemory().set("origin", true);
        ctx.registerEventTimeTimer("watch", Instant.ofEpochMilli(10), new Event("unexpected"));
        ctx.registerEventTimeTimer("watch", Instant.ofEpochMilli(10), tick(key, 10));
        ctx.registerEventTimeTimer("cancelled", Instant.ofEpochMilli(10), new Event("unexpected"));
        ctx.cancelTimer("cancelled");
    }

    private static Event tick(long key, long deadline) {
        return new Event("tick", Map.of("key", key, "deadline", deadline));
    }

    @Action("tick")
    public static void tick(Event event, RunnerContext ctx) throws Exception {
        long key = ((Number) event.getAttributes().get("key")).longValue();
        long deadline = ((Number) event.getAttributes().get("deadline")).longValue();
        assertThat(ctx.getSensoryMemory().isExist("origin")).isFalse();
        assertThat(event.getSourceTimestamp()).isEqualTo(deadline);
        ctx.sendEvent(new OutputEvent(key + ":" + deadline));
        if (deadline == 10) {
            ctx.registerEventTimeTimer("watch", Instant.ofEpochMilli(9), tick(key, 9));
        }
    }

    @Action("unexpected")
    public static void unexpected(Event event, RunnerContext ctx) {
        throw new AssertionError("Replaced or cancelled timer fired");
    }
}
