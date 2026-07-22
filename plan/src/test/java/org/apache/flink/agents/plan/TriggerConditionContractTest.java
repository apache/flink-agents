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

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.condition.TriggerCondition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerConditionContractTest {
    public static void handler(Event event, RunnerContext context) {}

    static class TriggerConditionAgent extends Agent {
        @org.apache.flink.agents.api.annotation.Action(" type == 'x' ")
        public void conditionOnly(Event event, RunnerContext context) {}

        @org.apache.flink.agents.api.annotation.Action({"x", " score > 1 ", "x"})
        public void mixedOr(Event event, RunnerContext context) {}

        @org.apache.flink.agents.api.annotation.Action("order-created")
        public void hyphenatedEventType(Event event, RunnerContext context) {}
    }

    private static JavaFunction function() throws Exception {
        return new JavaFunction(
                TriggerConditionContractTest.class.getName(),
                "handler",
                new Class[] {Event.class, RunnerContext.class});
    }

    @Test
    void planJsonOmitsDerivedRoutingState() throws Exception {
        Action action = new Action("a", function(), List.of("x", "score > 1"), null);
        Map<String, Action> source = new LinkedHashMap<>();
        source.put("a", action);
        AgentPlan plan = new AgentPlan(source);
        source.clear();

        assertThat(plan.getActions()).containsOnlyKeys("a");
        assertThatThrownBy(() -> plan.getActions().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        String json = new ObjectMapper().writeValueAsString(plan);
        assertThat(json).contains("trigger_conditions").doesNotContain("actions_by_event");
    }

    @Test
    void javaRoundTripRebuildsConditions() throws Exception {
        Action raw =
                new Action(
                        "raw", function(), List.of("x", " score > 1 ", "x", "type == 'x'"), null);
        AgentPlan restored = javaRoundTrip(new AgentPlan(Map.of("raw", raw)));

        assertThat(restored.getActions().get("raw").getTriggerConditions())
                .containsExactly("x", " score > 1 ", "x", "type == 'x'");
        assertThat(restored.getActions().get("raw").getClassifiedTriggerConditions())
                .containsExactly(
                        TriggerCondition.classify("x"),
                        TriggerCondition.classify("score > 1"),
                        TriggerCondition.classify("x"),
                        TriggerCondition.classify("type == 'x'"));
        assertThatThrownBy(() -> restored.getActions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> restored.getActions().get("raw").getTriggerConditions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void annotationKeepsConditionOrder() throws Exception {
        AgentPlan plan = new AgentPlan(new TriggerConditionAgent());

        assertThat(plan.getActions().get("conditionOnly").getTriggerConditions())
                .containsExactly(" type == 'x' ");
        assertThat(plan.getActions().get("mixedOr").getTriggerConditions())
                .containsExactly("x", " score > 1 ", "x");
        assertThat(plan.getActions().get("hyphenatedEventType").getClassifiedTriggerConditions())
                .containsExactly(TriggerCondition.classify("order-created"));
    }

    private static AgentPlan javaRoundTrip(AgentPlan plan) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(plan);
        }
        try (ObjectInputStream input =
                new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (AgentPlan) input.readObject();
        }
    }
}
