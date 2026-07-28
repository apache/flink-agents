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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlanJsonValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void returnsNullForValidPlan() throws Exception {
        String planJson =
                planJson(
                        List.of(
                                action(
                                        "valid",
                                        List.of(
                                                "_input_event",
                                                "type == EventType.InputEvent && input > 2"))));

        assertThat(AgentPlanJsonValidator.validateAgentPlan(planJson)).isNull();
    }

    @Test
    void returnsTriggerConditionErrorMessage() throws Exception {
        String source = "type ==";
        String planJson =
                planJson(
                        List.of(action("failing", List.of("_input_event", source, "other.event"))));

        String errorMessage = AgentPlanJsonValidator.validateAgentPlan(planJson);

        assertThat(errorMessage)
                .contains("trigger condition #2")
                .contains("action 'failing'")
                .contains(source);
    }

    @Test
    void returnsInvalidPlanShapeError() throws Exception {
        String planJson = planJson(List.of(action("empty", List.of())));

        String errorMessage = AgentPlanJsonValidator.validateAgentPlan(planJson);

        assertThat(errorMessage).contains("trigger_conditions");
    }

    private static ObjectNode action(String name, List<String> triggerConditions) {
        ObjectNode action = MAPPER.createObjectNode();
        action.put("name", name);
        ObjectNode exec = action.putObject("exec");
        exec.put("func_type", "PythonFunction");
        exec.put("module", "example");
        exec.put("qualname", "handle");
        ArrayNode conditions = action.putArray("trigger_conditions");
        triggerConditions.forEach(conditions::add);
        action.putNull("config");
        return action;
    }

    private static String planJson(List<ObjectNode> actions) throws Exception {
        ObjectNode plan = MAPPER.createObjectNode();
        ObjectNode actionMapping = plan.putObject("actions");
        for (ObjectNode action : actions) {
            actionMapping.set(action.get("name").asText(), action);
        }
        plan.putObject("resource_providers");
        plan.putObject("config").putObject("conf_data");
        return MAPPER.writeValueAsString(plan);
    }
}
