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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Field-based FunctionTool extraction and invocation tests. */
class AgentPlanDeclareToolFieldTest {

    private AgentPlan agentPlan;
    private ResourceCache resourceCache;

    // Static methods to be wrapped by FunctionTool
    public static double calculate(
            @ToolParam(name = "a") double a,
            @ToolParam(name = "b") double b,
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

    public static String getWeather(
            @ToolParam(name = "location") String location,
            @ToolParam(name = "units") String units) {
        double temp = "fahrenheit".equals(units) ? 72.0 : 22.0;
        return String.format(
                "Weather in %s: %.1f°%s, Sunny",
                location, temp, "fahrenheit".equals(units) ? "F" : "C");
    }

    static class TestAgent extends Agent {
        @org.apache.flink.agents.api.annotation.Tool
        private final Tool calculator = createCalculatorTool();

        @org.apache.flink.agents.api.annotation.Tool
        private final Tool weather = createWeatherTool();

        @Action(listenEvents = {InputEvent.class})
        public void onInput(Event e, RunnerContext ctx) {
            /* no-op */
        }

        private static Tool createCalculatorTool() {
            try {
                Method m =
                        AgentPlanDeclareToolFieldTest.class.getMethod(
                                "calculate", double.class, double.class, String.class);
                return FunctionTool.fromStaticMethod("Performs basic arithmetic operations", m);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static Tool createWeatherTool() {
            try {
                Method m =
                        AgentPlanDeclareToolFieldTest.class.getMethod(
                                "getWeather", String.class, String.class);
                return FunctionTool.fromStaticMethod("Get weather information for a location", m);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new TestAgent());
        resourceCache = new ResourceCache(agentPlan.getResourceProviders());
    }

    @Test
    @DisplayName("Extract FunctionTool resources into providers")
    void extractTools() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.TOOL));
        Map<String, ?> toolProviders = providers.get(ResourceType.TOOL);
        assertTrue(toolProviders.containsKey("calculator"));
        assertTrue(toolProviders.containsKey("weather"));
    }

    @Test
    @DisplayName("Retrieve FunctionTool and call with parameters")
    void callCalculator() throws Exception {
        Tool tool = (Tool) resourceCache.getResource("calculator", ResourceType.TOOL);
        assertInstanceOf(FunctionTool.class, tool);
        ToolResponse r =
                tool.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 15.0,
                                                "b", 3.0,
                                                "operation", "multiply"))));
        assertTrue(r.isSuccess());
        assertEquals(45.0, (Double) r.getResult(), 0.001);
    }

    @Test
    @DisplayName("Call weather FunctionTool")
    void callWeather() throws Exception {
        Tool tool = (Tool) resourceCache.getResource("weather", ResourceType.TOOL);
        assertInstanceOf(FunctionTool.class, tool);
        ToolResponse r =
                tool.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "location", "London",
                                                "units", "fahrenheit"))));
        assertTrue(r.isSuccess());
        assertTrue(r.getResultAsString().contains("London"));
        assertTrue(r.getResultAsString().contains("72.0°F"));
    }

    @Test
    @DisplayName("FunctionTool metadata and schema")
    void metadataSchema() throws Exception {
        FunctionTool tool =
                (FunctionTool) resourceCache.getResource("calculator", ResourceType.TOOL);
        ToolMetadata md = tool.getMetadata();
        assertEquals("calculate", md.getName());
        assertEquals("Performs basic arithmetic operations", md.getDescription());
        assertNotNull(md.getInputSchema());
        String json = md.getInputSchema();
        assertTrue(json.contains("\"a\""));
        assertTrue(json.contains("\"b\""));
        assertTrue(json.contains("\"operation\""));
    }

    @Test
    @DisplayName("FunctionTool error cases")
    void calculatorErrors() throws Exception {
        Tool tool = (Tool) resourceCache.getResource("calculator", ResourceType.TOOL);
        ToolResponse r =
                tool.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 10.0,
                                                "b", 0.0,
                                                "operation", "divide"))));
        assertFalse(r.isSuccess());

        r =
                tool.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 1.0,
                                                "b", 1.0,
                                                "operation", "noop"))));
        assertFalse(r.isSuccess());
    }
}
