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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Utils for generating agent plan json. */
public class GenerateAgentPlanJson {
    private static class MyEvent extends Event {}

    private static class MyAction extends Action {

        public static void doNothing(Event event, RunnerContext context) {
            // No operation
        }

        public MyAction() throws Exception {
            super(
                    "MyAction",
                    new JavaFunction(
                            MyAction.class.getName(),
                            "doNothing",
                            new Class[] {Event.class, RunnerContext.class}),
                    List.of(InputEvent.class.getName(), MyEvent.class.getName()));
        }
    }

    /** Generate agent plan json. */
    public static void main(String[] args) throws Exception {
        String jsonPath = args[0];
        JavaFunction function1 =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});
        JavaFunction function2 =
                new JavaFunction(
                        MyAction.class.getName(),
                        "doNothing",
                        new Class[] {Event.class, RunnerContext.class});

        // Create Actions
        Action action1 = new Action("first_action", function1, List.of(InputEvent.class.getName()));
        Action action2 =
                new Action(
                        "second_action",
                        function2,
                        List.of(InputEvent.class.getName(), MyEvent.class.getName()));

        // Create a map of actions
        Map<String, Action> actions = new HashMap<>();
        actions.put(action1.getName(), action1);
        actions.put(action2.getName(), action2);

        // Create a map of event trigger actions
        Map<String, List<Action>> actionsByEvent = new HashMap<>();
        actionsByEvent.put(InputEvent.class.getName(), List.of(action1, action2));
        actionsByEvent.put(MyEvent.class.getName(), List.of(action2));

        // Create a AgentPlan with both actions and event trigger actions
        AgentPlan agentPlan = new AgentPlan(actions, actionsByEvent);

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);
        BufferedWriter writer = new BufferedWriter(new FileWriter(jsonPath));
        writer.write(json);
        writer.close();
    }
}
