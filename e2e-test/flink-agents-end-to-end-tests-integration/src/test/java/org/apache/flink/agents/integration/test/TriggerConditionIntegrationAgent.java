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

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exercises trigger conditions through the complete action-routing path. */
public class TriggerConditionIntegrationAgent extends Agent {

    /** Java POJO input used to verify lazy nested-field normalization. */
    public static final class ConditionInput implements Serializable {
        public String id;
        public String status;
        public int value;

        public ConditionInput() {}

        public ConditionInput(String id, String status, int value) {
            this.id = id;
            this.status = status;
            this.value = value;
        }
    }

    /** Verifies that equivalent POJO and map payloads match the same nested condition. */
    public static final class PojoMapParityAgent extends Agent {

        @Action("type == EventType.InputEvent && input.status == 'ok'")
        public static void onMatchingStatus(Event event, RunnerContext ctx) {
            Object input = InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new OutputEvent("parity:" + inputId(input)));
        }
    }

    /** Verifies double-quoted bracket access for a map key that contains dots. */
    public static final class DoubleQuotedDottedMapKeyAgent extends Agent {

        @Action("type == EventType.InputEvent && input[\"com.www.info\"] == 'ready'")
        public static void onDottedKey(Event event, RunnerContext ctx) {
            Object input = InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new OutputEvent("double-quoted:" + inputId(input)));
        }
    }

    /** Verifies single-quoted bracket access for a map key that contains dots. */
    public static final class SingleQuotedDottedMapKeyAgent extends Agent {

        @Action("type == EventType.InputEvent && input['com.www.info'] == 'ready'")
        public static void onDottedKey(Event event, RunnerContext ctx) {
            Object input = InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new OutputEvent("single-quoted:" + inputId(input)));
        }
    }

    /** Verifies an AND expression over two fields in a nested input map. */
    public static final class NestedMapAndAgent extends Agent {

        @Action(
                "type == EventType.InputEvent "
                        + "&& input.status.forward == true "
                        + "&& input.status.code == 'ready'")
        public static void onNestedStatus(Event event, RunnerContext ctx) {
            Object input = InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new OutputEvent("nested-map:" + inputId(input)));
        }
    }

    /** Verifies whole-value access for scalar and list input payloads. */
    public static final class ScalarListPayloadAgent extends Agent {

        @Action({
            "type == EventType.InputEvent && input == 'ready'",
            "type == EventType.InputEvent && input[0] == 'ready'"
        })
        public static void onMatchingPayload(Event event, RunnerContext ctx) {
            Object input = InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new OutputEvent(input instanceof List ? "list" : "scalar"));
        }
    }

    @Action("type == EventType.InputEvent && input.status == 'ok'")
    public static void onNestedPojoField(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("nested:" + input(event).id));
    }

    @Action("type == EventType.InputEvent && attributes.input.status == 'ok'")
    public static void onAttributesNamespace(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("envelope:" + input(event).id));
    }

    @Action({
        "type == EventType.InputEvent && input.status == 'ok'",
        "type == EventType.InputEvent && input.value > 5"
    })
    public static void onStatusOrValue(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("or:" + input(event).id));
    }

    @Action(EventType.InputEvent)
    public static void emitCustomEvent(Event event, RunnerContext ctx) {
        ConditionInput input = input(event);
        Map<String, Object> result = new HashMap<>();
        result.put("id", input.id);
        result.put("status", input.status);
        result.put("value", input.value);
        if ("ok".equals(input.status)) {
            result.put("details", Map.of("code", "ready"));
        }
        ctx.sendEvent(new Event("condition.result", Map.of("result", result)));
    }

    /** YAML action that emits the bare hyphenated event type used by the routing E2E. */
    public static void emitYamlOrderCreated(Event event, RunnerContext ctx) {
        Event orderCreated = new Event("order-created");
        orderCreated.setAttr("id", input(event).id);
        ctx.sendEvent(orderCreated);
    }

    /** YAML action that confirms a bare hyphenated event type reached the type index. */
    public static void onYamlOrderCreated(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("yaml-hyphen:" + event.getAttr("id")));
    }

    @Action("type == 'condition.result' && result.status == 'ok'")
    public static void onCustomAttribute(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("custom:" + resultId(event)));
    }

    @Action(
            "type == 'condition.result' "
                    + "&& has(result.details.code) "
                    + "&& result.details.code == 'ready'")
    public static void onGuardedNestedAttribute(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("guarded:" + resultId(event)));
    }

    /** Terminal output events must not be routed back into actions. */
    @Action(EventType.OutputEvent)
    public static void onTerminalOutput(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("unexpected-output-routing"));
    }

    private static ConditionInput input(Event event) {
        return (ConditionInput) InputEvent.fromEvent(event).getInput();
    }

    private static String inputId(Object input) {
        if (input instanceof ConditionInput) {
            return ((ConditionInput) input).id;
        }
        if (input instanceof Map) {
            return (String) ((Map<?, ?>) input).get("id");
        }
        throw new IllegalArgumentException("Unsupported parity payload: " + input.getClass());
    }

    @SuppressWarnings("unchecked")
    private static String resultId(Event event) {
        return (String) ((Map<String, Object>) event.getAttr("result")).get("id");
    }
}
