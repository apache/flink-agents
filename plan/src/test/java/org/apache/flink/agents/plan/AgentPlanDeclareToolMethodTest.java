/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanDeclareToolMethodTest {

    private AgentPlan agentPlan;

    static class TestAgent extends Agent {
        @org.apache.flink.agents.api.annotation.Tool(
                description = "Performs basic arithmetic operations")
        public static double calculate(
                @ToolParam(name = "a") Double a,
                @ToolParam(name = "b") Double b,
                @ToolParam(name = "operation") String operation) {
            switch (operation.toLowerCase()) {
                case "add":
                    return a + b;
                case "subtract":
                    return a - b;
                case "multiply":
                    return a * b;
                case "divide":
                    if (b == 0) throw new IllegalArgumentException("Division by zero");
                    return a / b;
                default:
                    throw new IllegalArgumentException("Unknown operation: " + operation);
            }
        }

        @org.apache.flink.agents.api.annotation.Tool(
                description = "Get weather information for a location")
        public static String getWeather(
                @ToolParam(name = "location") String location,
                @ToolParam(name = "units") String units) {
            double temp = "fahrenheit".equals(units) ? 72.0 : 22.0;
            return String.format(
                    "Weather in %s: %.1f°%s, Sunny",
                    location, temp, "fahrenheit".equals(units) ? "F" : "C");
        }

        @Action(listenEvents = {InputEvent.class})
        public void process(Event event, RunnerContext ctx) {
            // no-op
        }
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new TestAgent());
    }

    @Test
    @DisplayName("Discover static @Tool methods and register providers")
    void discoverTools() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.TOOL));
        Map<String, ?> toolProviders = providers.get(ResourceType.TOOL);
        assertTrue(toolProviders.containsKey("calculate"));
        assertTrue(toolProviders.containsKey("getWeather"));
    }

    void checkToolCall(AgentPlan agentPlan) throws Exception {
        Tool calculator = (Tool) agentPlan.getResource("calculate", ResourceType.TOOL);
        ToolParameters tp =
                new ToolParameters(
                        new HashMap<>(
                                Map.of(
                                        "a", 15.0,
                                        "b", 3.0,
                                        "operation", "multiply")));
        ToolResponse r = calculator.call(tp);
        assertTrue(r.isSuccess());
        assertEquals(45.0, (Double) r.getResult(), 0.001);

        Tool weather = (Tool) agentPlan.getResource("getWeather", ResourceType.TOOL);
        ToolResponse wr =
                weather.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "location", "London",
                                                "units", "fahrenheit"))));
        assertTrue(wr.isSuccess());
        assertTrue(wr.getResultAsString().contains("London"));
        assertTrue(wr.getResultAsString().contains("72.0°F"));
    }

    @Test
    @DisplayName("Retrieve tool and call with parameters")
    void retrieveAndCallTool() throws Exception {
        checkToolCall(this.agentPlan);
    }

    @Test
    @DisplayName("Check tools added to agent instance.")
    void testAgentAddTool() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                        "calculate",
                        ResourceType.TOOL,
                        Tool.fromMethod(
                                TestAgent.class.getMethod(
                                        "calculate", Double.class, Double.class, String.class)))
                .addResource(
                        "getWeather",
                        ResourceType.TOOL,
                        Tool.fromMethod(
                                TestAgent.class.getMethod(
                                        "getWeather", String.class, String.class)));
        checkToolCall(new AgentPlan(agent));
    }

    @Test
    @DisplayName("Parameter conversion and errors")
    void paramConversionAndErrors() throws Exception {
        Tool calculator = (Tool) agentPlan.getResource("calculate", ResourceType.TOOL);

        ToolResponse r =
                calculator.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a",
                                                10, // int
                                                "b",
                                                2.5, // double
                                                "operation",
                                                "divide"))));
        assertTrue(r.isSuccess());
        assertEquals(4.0, (Double) r.getResult(), 0.001);

        r =
                calculator.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", "20",
                                                "b", "4",
                                                "operation", "subtract"))));
        assertTrue(r.isSuccess());
        assertEquals(16.0, (Double) r.getResult(), 0.001);

        // Division by zero
        r =
                calculator.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 10.0,
                                                "b", 0.0,
                                                "operation", "divide"))));
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());

        // Invalid operation
        r =
                calculator.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 1.0,
                                                "b", 1.0,
                                                "operation", "noop"))));
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
    }

    @Test
    @DisplayName("Metadata and schema shape")
    void metadataSchema() throws Exception {
        Tool calculator = (Tool) agentPlan.getResource("calculate", ResourceType.TOOL);
        ToolMetadata md = calculator.getMetadata();
        assertEquals("calculate", md.getName());
        assertEquals("Performs basic arithmetic operations", md.getDescription());
        assertNotNull(md.getInputSchema());
        String json = md.getInputSchema();
        assertTrue(json.contains("\"a\""));
        assertTrue(json.contains("\"b\""));
        assertTrue(json.contains("\"operation\""));
    }

    @Test
    @DisplayName("AgentPlan json serialize and deserialized with ToolResourceProvider")
    void testAgentPlanJsonSerializable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(agentPlan);
        AgentPlan restored = mapper.readValue(json, AgentPlan.class);
        Tool calculator = (Tool) restored.getResource("calculate", ResourceType.TOOL);
        ToolResponse r =
                calculator.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 6.0,
                                                "b", 7.0,
                                                "operation", "multiply"))));
        assertTrue(r.isSuccess());
        assertEquals(42.0, (Double) r.getResult(), 0.001);
    }
}
