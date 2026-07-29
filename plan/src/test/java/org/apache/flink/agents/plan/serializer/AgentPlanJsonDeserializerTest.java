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
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgentPlanJsonDeserializerTest {
    @Test
    public void testDeserialize() throws Exception {
        // Read JSON for an Action with JavaFunction from resource file
        String json = Utils.readJsonFromResource("agent_plans/agent_plan.json");
        AgentPlan agentPlan = new ObjectMapper().readValue(json, AgentPlan.class);
        assertEquals(2, agentPlan.getActions().size());

        // Check the first action
        assertTrue(agentPlan.getActions().containsKey("first_action"));
        Action firstAction = agentPlan.getActions().get("first_action");
        assertInstanceOf(JavaFunction.class, firstAction.getExec());
        assertEquals(List.of(InputEvent.EVENT_TYPE), firstAction.getListenEventTypes());

        // Check the second action
        assertTrue(agentPlan.getActions().containsKey("second_action"));
        Action secondAction = agentPlan.getActions().get("second_action");
        assertInstanceOf(JavaFunction.class, secondAction.getExec());
        assertEquals(
                List.of(InputEvent.EVENT_TYPE, MyEvent.EVENT_TYPE),
                secondAction.getListenEventTypes());

        // Check event trigger actions
        assertEquals(2, agentPlan.getActionsByEvent().size());
        assertTrue(agentPlan.getActionsByEvent().containsKey(InputEvent.EVENT_TYPE));
        assertEquals(
                List.of(firstAction, secondAction),
                agentPlan.getActionsByEvent().get(InputEvent.EVENT_TYPE));
        assertEquals(List.of(secondAction), agentPlan.getActionsByEvent().get(MyEvent.EVENT_TYPE));

        // Check the flink agent config
        Map<String, Object> configData = agentPlan.getConfigData();
        assertEquals(4, configData.keySet().size());
        assertEquals(1, configData.get("key1"));
        assertEquals(1.5, configData.get("key2"));
        assertEquals(true, configData.get("key3"));
        assertEquals("v1", configData.get("key4"));
    }

    @Test
    public void testDeserializeToolWithInjectedArgs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FunctionTool originalTool =
                new FunctionTool(
                        new ToolMetadata("query_order", "Query order.", "{}"),
                        new JavaFunction(
                                MyAction.class.getName(),
                                "doNothing",
                                new Class[] {Event.class, RunnerContext.class}),
                        Map.of(
                                "tenant_id",
                                ToolParameterInjection.fromSensoryMemory("request.tenant_id")));

        String json =
                mapper.writeValueAsString(
                        Map.of(
                                "actions",
                                Map.of(),
                                "actions_by_event",
                                Map.of(),
                                "resource_providers",
                                Map.of(
                                        "tool",
                                        Map.of(
                                                "query_order",
                                                Map.of(
                                                        "name",
                                                        "query_order",
                                                        "type",
                                                        "tool",
                                                        "module",
                                                        FunctionTool.class.getPackageName(),
                                                        "clazz",
                                                        FunctionTool.class.getName(),
                                                        "serializedResource",
                                                        mapper.writeValueAsString(originalTool),
                                                        "__resource_provider_type__",
                                                        "JavaSerializableResourceProvider")))));

        AgentPlan agentPlan = mapper.readValue(json, AgentPlan.class);

        ResourceProvider provider =
                agentPlan.getResourceProviders().get(ResourceType.TOOL).get("query_order");
        FunctionTool deserializedTool =
                (FunctionTool) provider.provide(ResourceContext.fromGetResource((n, t) -> null));
        assertEquals(
                Map.of("tenant_id", ToolParameterInjection.fromSensoryMemory("request.tenant_id")),
                deserializedTool.getInjectedArgs());
    }

    private static class MyEvent extends Event {
        public static final String EVENT_TYPE = "MyEvent";

        public MyEvent() {
            super(EVENT_TYPE);
        }
    }

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
                    List.of(InputEvent.EVENT_TYPE, MyEvent.EVENT_TYPE));
        }
    }
}
