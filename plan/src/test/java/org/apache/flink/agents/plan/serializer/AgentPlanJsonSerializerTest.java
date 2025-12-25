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

package org.apache.flink.agents.plan.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link AgentPlanJsonSerializer}. */
public class AgentPlanJsonSerializerTest {

    /** Test Agent class with @Action annotated methods. */
    public static class TestAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(listenEvents = {InputEvent.class})
        public void handleInputEvent(InputEvent event, RunnerContext context) {
            // Test action logic
        }

        @org.apache.flink.agents.api.annotation.Action(listenEvents = {OutputEvent.class})
        public void processOutputEvent(OutputEvent event, RunnerContext context) {
            // Test action logic
        }

        @org.apache.flink.agents.api.annotation.Action(
                listenEvents = {InputEvent.class, OutputEvent.class})
        public void handleMultipleEvents(Event event, RunnerContext context) {
            // Test action logic for multiple event types
        }

        // Non-annotated method should be ignored
        public void regularMethod() {
            // This should not be included in the AgentPlan
        }
    }

    @Test
    public void testSerializeAgentPlanWithActions() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create an Action
        Action action = new Action("testAction", function, List.of(InputEvent.class.getName()));

        // Create a map of actions
        Map<String, Action> actions = new HashMap<>();
        actions.put(action.getName(), action);

        // Create a AgentPlan with actions but no event trigger actions
        AgentPlan agentPlan = new AgentPlan(actions, new HashMap<>());

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);

        // Verify the JSON contains the expected fields
        assertThat(json).contains("\"actions\":{");
        assertThat(json).contains("\"testAction\":{");
        assertThat(json).contains("\"name\":\"testAction\"");
        assertThat(json).contains("\"exec\":{");
        assertThat(json).contains("\"func_type\":\"JavaFunction\"");
        assertThat(json).contains("\"qualname\":\"org.apache.flink.agents.plan.TestAction\"");
        assertThat(json).contains("\"method_name\":\"legal\"");
        assertThat(json).contains("\"listen_event_types\":[");
        assertThat(json).contains("\"org.apache.flink.agents.api.InputEvent\"");
        assertThat(json).contains("\"actions_by_event\":{}");
    }

    @Test
    public void testSerializeAgentPlanWithActionsByEvent() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create an Action
        Action action = new Action("testAction", function, List.of(InputEvent.class.getName()));

        // Create a map of event trigger actions
        Map<String, List<Action>> actionsByEvent = new HashMap<>();
        actionsByEvent.put(InputEvent.class.getName(), List.of(action));

        // Create a AgentPlan with event trigger actions but no regular actions
        AgentPlan agentPlan = new AgentPlan(new HashMap<>(), actionsByEvent);

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);

        // Verify the JSON contains the expected fields
        assertThat(json).contains("\"actions\":{}");
        assertThat(json).contains("\"actions_by_event\":{");
        assertThat(json).contains("\"org.apache.flink.agents.api.InputEvent\":[");
        assertThat(json).contains("\"testAction\"");
    }

    @Test
    public void testSerializeAgentPlanWithBothActionsAndActionsByEvent() throws Exception {
        // Create JavaFunctions
        JavaFunction function1 =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});
        JavaFunction function2 =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create Actions
        Action action1 = new Action("action1", function1, List.of(InputEvent.class.getName()));
        Action action2 = new Action("action2", function2, List.of(OutputEvent.class.getName()));

        // Create a map of actions
        Map<String, Action> actions = new HashMap<>();
        actions.put(action1.getName(), action1);
        actions.put(action2.getName(), action2);

        // Create a map of event trigger actions
        Map<String, List<Action>> actionsByEvent = new HashMap<>();
        actionsByEvent.put(InputEvent.class.getName(), List.of(action1));
        actionsByEvent.put(OutputEvent.class.getName(), List.of(action2));

        // Create a AgentPlan with both actions and event trigger actions
        AgentPlan agentPlan = new AgentPlan(actions, actionsByEvent);

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);

        // Verify the JSON contains the expected fields
        assertThat(json).contains("\"actions\":{");
        assertThat(json).contains("\"action1\":{");
        assertThat(json).contains("\"action2\":{");
        assertThat(json).contains("\"actions_by_event\":{");
        assertThat(json).contains("\"org.apache.flink.agents.api.InputEvent\":[");
        assertThat(json).contains("\"org.apache.flink.agents.api.OutputEvent\":[");
        assertThat(json).contains("\"action1\"");
        assertThat(json).contains("\"action2\"");
    }

    @Test
    public void testSerializeEmptyAgentPlan() throws Exception {
        // Create an empty AgentPlan
        AgentPlan agentPlan = new AgentPlan(new HashMap<>(), new HashMap<>());

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);

        // Verify the JSON contains the expected fields
        assertThat(json).contains("\"actions\":{}");
        assertThat(json).contains("\"actions_by_event\":{}");
    }

    @Test
    public void testSerializeAgentPlanCreatedFromAgent() throws Exception {
        // Create a test agent
        TestAgent agent = new TestAgent();

        Map<String, Object> confData = new HashMap<>();
        confData.put("config.key", "config.value");
        AgentConfiguration agentConfiguration = new AgentConfiguration(confData);

        // Create AgentPlan from the agent
        AgentPlan agentPlan = new AgentPlan(agent, agentConfiguration);

        // Serialize the agent plan to JSON
        String json = new ObjectMapper().writeValueAsString(agentPlan);

        // Verify the JSON contains the expected fields
        assertThat(json).contains("\"actions\":{");
        assertThat(json).contains("\"actions_by_event\":{");
        assertThat(json).contains("\"config\":{");

        // Verify that the actions from @Action annotated methods are present
        assertThat(json).contains("\"handleInputEvent\"");
        assertThat(json).contains("\"processOutputEvent\"");
        assertThat(json).contains("\"handleMultipleEvents\"");

        // Verify event mappings
        assertThat(json).contains("\"org.apache.flink.agents.api.InputEvent\"");
        assertThat(json).contains("\"org.apache.flink.agents.api.OutputEvent\"");

        // Verify function details
        assertThat(json).contains("\"func_type\":\"JavaFunction\"");
        assertThat(json)
                .contains(
                        "\"qualname\":\"org.apache.flink.agents.plan.serializer.AgentPlanJsonSerializerTest$TestAgent\"");

        // Verify that regularMethod is not included (not annotated)
        assertThat(json).doesNotContain("\"regularMethod\"");

        // Verify that config data from AgentConfiguration is present
        assertThat(json).contains("\"conf_data\":{\"config.key\":\"config.value\"}");
    }
}
