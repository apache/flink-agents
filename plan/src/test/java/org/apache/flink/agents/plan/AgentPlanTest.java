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

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link AgentPlan} constructor that takes an Agent. */
public class AgentPlanTest {

    /** Test event class for testing. */
    public static class TestEvent extends Event {
        private final String data;

        public TestEvent(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }
    }

    /** Test agent class with annotated methods. */
    public static class TestAgent extends Agent {

        @org.apache.flink.agents.api.Action(listenEvents = {InputEvent.class})
        public void handleInputEvent(InputEvent event, RunnerContext context) {
            // Test action implementation
        }

        @org.apache.flink.agents.api.Action(listenEvents = {TestEvent.class, OutputEvent.class})
        public void handleMultipleEvents(Event event, RunnerContext context) {
            // Test action implementation
        }

        // This method is not annotated, so it should not be included
        public void nonAnnotatedMethod(Event event, RunnerContext context) {
            // This should not be included in the agent plan
        }
    }

    @Test
    public void testConstructorWithAgent() throws Exception {
        // Create an agent instance
        TestAgent agent = new TestAgent();

        // Create AgentPlan using the new constructor
        AgentPlan agentPlan = new AgentPlan(agent);

        // Verify that actions were collected correctly
        assertThat(agentPlan.getActions().size()).isEqualTo(2);
        assertThat(agentPlan.getActions()).containsKey("handleInputEvent");
        assertThat(agentPlan.getActions()).containsKey("handleMultipleEvents");

        // Verify action details for handleInputEvent
        Action inputAction = agentPlan.getActions().get("handleInputEvent");
        assertThat(inputAction).isNotNull();
        assertThat(inputAction.getName()).isEqualTo("handleInputEvent");
        assertThat(inputAction.getListenEventTypes())
                .isEqualTo(List.of(InputEvent.class.getName()));
        assertThat(inputAction.getExec()).isInstanceOf(JavaFunction.class);

        // Check that the JavaFunction instance has the correct method/class/params
        JavaFunction exec = (JavaFunction) inputAction.getExec();
        assertThat(exec.getQualName()).isEqualTo(TestAgent.class.getName());
        assertThat(exec.getMethodName()).isEqualTo("handleInputEvent");
        assertThat(exec.getParameterTypes()).containsExactly(InputEvent.class, RunnerContext.class);

        // Verify action details for handleMultipleEvents
        Action multiAction = agentPlan.getActions().get("handleMultipleEvents");
        assertThat(multiAction).isNotNull();
        assertThat(multiAction.getName()).isEqualTo("handleMultipleEvents");
        assertThat(multiAction.getListenEventTypes())
                .isEqualTo(List.of(TestEvent.class.getName(), OutputEvent.class.getName()));
        assertThat(multiAction.getExec()).isInstanceOf(JavaFunction.class);

        // Verify actionsByEvent mapping
        assertThat(agentPlan.getActionsByEvent().size()).isEqualTo(3);

        // Check InputEvent mapping
        List<Action> inputEventActions =
                agentPlan.getActionsByEvent().get(InputEvent.class.getName());
        assertThat(inputEventActions).isNotNull();
        assertThat(inputEventActions.size()).isEqualTo(1);
        assertThat(inputEventActions.get(0).getName()).isEqualTo("handleInputEvent");

        // Check TestEvent mapping
        List<Action> testEventActions =
                agentPlan.getActionsByEvent().get(TestEvent.class.getName());
        assertThat(testEventActions).isNotNull();
        assertThat(testEventActions.size()).isEqualTo(1);
        assertThat(testEventActions.get(0).getName()).isEqualTo("handleMultipleEvents");

        // Check OutputEvent mapping
        List<Action> outputEventActions =
                agentPlan.getActionsByEvent().get(OutputEvent.class.getName());
        assertThat(outputEventActions).isNotNull();
        assertThat(outputEventActions.size()).isEqualTo(1);
        assertThat(outputEventActions.get(0).getName()).isEqualTo("handleMultipleEvents");
    }

    @Test
    public void testConstructorWithAgentNoActions() throws Exception {
        // Create an agent with no annotated methods
        Agent emptyAgent = new Agent() {
                    // No annotated methods
                };

        // Create AgentPlan using the new constructor
        AgentPlan agentPlan = new AgentPlan(emptyAgent);

        // Verify that no actions were collected
        assertThat(agentPlan.getActions().size()).isEqualTo(0);
        assertThat(agentPlan.getActionsByEvent().size()).isEqualTo(0);
    }
}
