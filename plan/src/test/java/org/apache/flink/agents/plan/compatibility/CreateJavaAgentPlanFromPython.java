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

package org.apache.flink.agents.plan.compatibility;

import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Utils for testing agent plan compatibility between Python and Java. */
public class CreateJavaAgentPlanFromPython {

    /** Test creating Java AgentPlan from Python AgentPlan json. */
    public static void main(String[] args) throws IOException {
        String agentJsonFile = args[0];
        System.out.println(agentJsonFile);
        String json = Files.readString(Paths.get(agentJsonFile));
        AgentPlan agentPlan = new ObjectMapper().readValue(json, AgentPlan.class);
        assertEquals(2, agentPlan.getActions().size());

        // Check the first action
        assertTrue(agentPlan.getActions().containsKey("first_action"));
        Action firstAction = agentPlan.getActions().get("first_action");
        assertInstanceOf(PythonFunction.class, firstAction.getExec());
        assertEquals(
                List.of("flink_agents.api.event.InputEvent"), firstAction.getListenEventTypes());

        // Check the second action
        assertTrue(agentPlan.getActions().containsKey("second_action"));
        Action secondAction = agentPlan.getActions().get("second_action");
        assertInstanceOf(PythonFunction.class, secondAction.getExec());
        assertEquals(
                List.of(
                        "flink_agents.api.event.InputEvent",
                        "flink_agents.plan.tests.test_agent_plan.MyEvent"),
                secondAction.getListenEventTypes());

        // Check event trigger actions
        assertEquals(2, agentPlan.getActionsByEvent().size());
        assertTrue(agentPlan.getActionsByEvent().containsKey("flink_agents.api.event.InputEvent"));
        assertTrue(
                agentPlan
                        .getActionsByEvent()
                        .containsKey("flink_agents.plan.tests.test_agent_plan.MyEvent"));
        assertEquals(
                List.of(firstAction, secondAction),
                agentPlan.getActionsByEvent().get("flink_agents.api.event.InputEvent"));
        assertEquals(
                List.of(secondAction),
                agentPlan
                        .getActionsByEvent()
                        .get("flink_agents.plan.tests.test_agent_plan.MyEvent"));
    }
}
