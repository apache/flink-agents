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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;

import java.io.BufferedWriter;
import java.io.FileWriter;

/**
 * Utils for generating agent plan json.
 *
 * <p>The agent plan json will be checked by
 * flink-agents/python/flink_agents/plan/tests/compatibility/create_python_agent_plan_from_json.py,
 * correspond modification should be applied to it when modify this file.
 */
public class GenerateAgentPlanJson {
    private static class MyEvent extends Event {}

    /** Agent class for generating java agent plan json. */
    public static class JavaAgentPlanCompatibilityTestAgent extends Agent {

        @Action(listenEvents = {InputEvent.class})
        public void firstAction(InputEvent event, RunnerContext context) {
            // Test action implementation
        }

        @Action(listenEvents = {InputEvent.class, MyEvent.class})
        public void secondAction(Event event, RunnerContext context) {
            // Test action implementation
        }
    }

    /** Generate agent plan json. */
    public static void main(String[] args) throws Exception {
        String jsonPath = args[0];

        AgentPlan agentPlan = new AgentPlan(new JavaAgentPlanCompatibilityTestAgent());

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);
        BufferedWriter writer = new BufferedWriter(new FileWriter(jsonPath));
        writer.write(json);
        writer.close();
    }
}
