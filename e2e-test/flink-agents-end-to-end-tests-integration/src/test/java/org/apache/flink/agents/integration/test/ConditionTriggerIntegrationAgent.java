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
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;

/**
 * Agent exercising unified {@code @Action} selectors end-to-end: type-only, condition-only,
 * compound conditions, custom types, and terminal-output behavior.
 */
public class ConditionTriggerIntegrationAgent extends Agent {

    @Action(EventType.InputEvent)
    public static void onAnyInput(Event event, RunnerContext ctx) {
        Object input = InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new OutputEvent("all:" + input));
    }

    @Action("type == EventType.InputEvent && input > 5")
    public static void onHighInput(Event event, RunnerContext ctx) {
        Object input = InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new OutputEvent("high:" + input));
    }

    @Action("type == '_input_event' && has(input) && input > 5")
    public static void onGuardedHighInput(Event event, RunnerContext ctx) {
        Object input = InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new OutputEvent("guard:" + input));
    }

    /** Re-emits every input as a custom dotted/hyphenated event type. */
    @Action(EventType.InputEvent)
    public static void reEmitAsCustomType(Event event, RunnerContext ctx) {
        Object input = InputEvent.fromEvent(event).getInput();
        Event custom = new Event("com.example.order-scored");
        custom.setAttr("v", input);
        ctx.sendEvent(custom);
    }

    /** Dotted/hyphenated custom type routed via the type index. */
    @Action("'com.example.order-scored'")
    public static void onQuotedCustomType(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("quoted:" + event.getAttr("v")));
    }

    /** Multiple allowed types and the predicate are combined inside one CEL expression. */
    @Action("(type == EventType.InputEvent || type == 'never-emitted') && input > 5")
    public static void onHighInputFromEitherType(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("multi:" + InputEvent.fromEvent(event).getInput()));
    }

    /** CEL short-circuiting avoids the missing attribute for every emitted event type. */
    @Action("type == 'never-emitted' && attributes.missing > 0")
    public static void onNeverEmitted(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("unexpected-non-target-evaluation"));
    }

    /** OutputEvent is a valid selector value but terminal outputs bypass action dispatch. */
    @Action(EventType.OutputEvent)
    public static void onTerminalOutput(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("unexpected-output-routing"));
    }
}
