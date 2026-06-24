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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        @org.apache.flink.agents.api.annotation.Tool(description = "Query an order")
        public static String queryOrder(
                @ToolParam(name = "order_id") String orderId,
                @ToolParam(
                                name = "tenant_id",
                                injected = true,
                                source = ToolParameterSource.CONFIG,
                                key = "tenant.id")
                        String tenantId) {
            return tenantId + ":" + orderId;
        }

        @Action(EventType.InputEvent)
        public void process(Event event, RunnerContext ctx) {
            // no-op
        }
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new TestAgent());
    }

    /** Resolves a resource directly from its provider, bypassing ResourceCache. */
    private Resource resolveResource(String name, ResourceType type) throws Exception {
        return agentPlan
                .getResourceProviders()
                .get(type)
                .get(name)
                .provide(
                        ResourceContext.fromGetResource(
                                (n, t) -> {
                                    throw new UnsupportedOperationException(
                                            "No dependencies expected");
                                }));
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

    void checkToolCall(AgentPlan plan) throws Exception {
        Tool calculator =
                (Tool)
                        plan.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("calculate")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));
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

        Tool weather =
                (Tool)
                        plan.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("getWeather")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));
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
        AgentPlan addedPlan = new AgentPlan(agent);
        checkToolCall(addedPlan);
    }

    @Test
    @DisplayName("Validate injected args on tools added to agent instance")
    void validateInjectedArgsOnAgentAddedTool() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "getWeather",
                ResourceType.TOOL,
                new org.apache.flink.agents.api.tools.FunctionTool(
                        org.apache.flink.agents.api.function.JavaFunction.fromMethod(
                                TestAgent.class.getMethod(
                                        "getWeather", String.class, String.class)),
                        Map.of("tenent_id", ToolParameterInjection.fromConfig("tenant_id"))));

        assertThatThrownBy(() -> new AgentPlan(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown parameter")
                .hasMessageContaining("tenent_id");
    }

    @Test
    @DisplayName("Injected args declared on API function tool are hidden from Java metadata")
    void hideApiFunctionToolInjectedArgsFromJavaMetadata() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "getWeather",
                ResourceType.TOOL,
                new org.apache.flink.agents.api.tools.FunctionTool(
                        org.apache.flink.agents.api.function.JavaFunction.fromMethod(
                                TestAgent.class.getMethod(
                                        "getWeather", String.class, String.class)),
                        Map.of("units", ToolParameterInjection.fromConfig("units"))));

        AgentPlan plan = new AgentPlan(agent);
        Tool weather =
                (Tool)
                        plan.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("getWeather")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));
        String schema = weather.getMetadata().getInputSchema();
        JsonNode properties = new ObjectMapper().readTree(schema).get("properties");

        assertTrue(properties.has("location"));
        assertFalse(properties.has("units"));
    }

    @Test
    @DisplayName("Annotation-only injected args are preserved on API function tools")
    void preserveAnnotationOnlyInjectedArgsOnApiFunctionTool() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "queryOrder",
                ResourceType.TOOL,
                new org.apache.flink.agents.api.tools.FunctionTool(
                        org.apache.flink.agents.api.function.JavaFunction.fromMethod(
                                TestAgent.class.getMethod(
                                        "queryOrder", String.class, String.class))));

        AgentPlan plan = new AgentPlan(agent);
        FunctionTool tool =
                (FunctionTool)
                        plan.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("queryOrder")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));
        JsonNode properties =
                new ObjectMapper().readTree(tool.getMetadata().getInputSchema()).get("properties");

        assertEquals(
                Map.of("tenant_id", ToolParameterInjection.fromConfig("tenant.id")),
                tool.getInjectedArgs());
        assertTrue(properties.has("order_id"));
        assertFalse(properties.has("tenant_id"));
    }

    @Test
    @DisplayName("Matching descriptor injected args are accepted with Java annotations")
    void acceptMatchingDescriptorInjectedArgsWithJavaAnnotations() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "queryOrder",
                ResourceType.TOOL,
                new org.apache.flink.agents.api.tools.FunctionTool(
                        org.apache.flink.agents.api.function.JavaFunction.fromMethod(
                                TestAgent.class.getMethod(
                                        "queryOrder", String.class, String.class)),
                        Map.of("tenant_id", ToolParameterInjection.fromConfig("tenant.id"))));

        AgentPlan plan = new AgentPlan(agent);
        FunctionTool tool =
                (FunctionTool)
                        plan.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("queryOrder")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));

        assertEquals(
                Map.of("tenant_id", ToolParameterInjection.fromConfig("tenant.id")),
                tool.getInjectedArgs());
    }

    @Test
    @DisplayName("Conflicting descriptor injected args fail fast with Java annotations")
    void rejectConflictingDescriptorInjectedArgsWithJavaAnnotations() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "queryOrder",
                ResourceType.TOOL,
                new org.apache.flink.agents.api.tools.FunctionTool(
                        org.apache.flink.agents.api.function.JavaFunction.fromMethod(
                                TestAgent.class.getMethod(
                                        "queryOrder", String.class, String.class)),
                        Map.of(
                                "tenant_id",
                                ToolParameterInjection.fromSensoryMemory("request.tenant_id"))));

        assertThatThrownBy(() -> new AgentPlan(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("injected_args conflict")
                .hasMessageContaining("tenant_id");
    }

    @Test
    @DisplayName("YAML-declared injected args are hidden from Java metadata")
    void hideYamlInjectedArgsFromJavaMetadata(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("agent.yaml");
        Files.writeString(
                yaml,
                "agents:\n"
                        + "  - name: a\n"
                        + "    tools:\n"
                        + "      - name: getWeather\n"
                        + "        type: java\n"
                        + "        function: "
                        + TestAgent.class.getName()
                        + ":getWeather\n"
                        + "        parameter_types: [java.lang.String, java.lang.String]\n"
                        + "        injected_args:\n"
                        + "          units:\n"
                        + "            source: config\n"
                        + "            key: units\n");

        Agent agent = YamlLoader.buildAgents(yaml).getAgents().get("a");
        AgentPlan plan = new AgentPlan(agent);
        Tool weather =
                (Tool)
                        plan.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("getWeather")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));
        JsonNode properties =
                new ObjectMapper()
                        .readTree(weather.getMetadata().getInputSchema())
                        .get("properties");

        assertTrue(properties.has("location"));
        assertFalse(properties.has("units"));
    }

    @Test
    @DisplayName("Parameter conversion and errors")
    void paramConversionAndErrors() throws Exception {
        Tool calculator = (Tool) resolveResource("calculate", ResourceType.TOOL);

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
        Tool calculator = (Tool) resolveResource("calculate", ResourceType.TOOL);
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
        Tool calculator =
                (Tool)
                        restored.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get("calculate")
                                .provide(
                                        ResourceContext.fromGetResource(
                                                (n, t) -> {
                                                    throw new UnsupportedOperationException(
                                                            "No dependencies expected");
                                                }));
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
