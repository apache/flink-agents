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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.WorkflowPlan;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkflowPlanJsonDeserializerTest {
    @Test
    public void testDeserialize() throws Exception {
        // Read JSON for an Action with JavaFunction from resource file
        String json = readJsonFromResource("workflow_plans/workflow_plan.json");
        WorkflowPlan workflowPlan = new ObjectMapper().readValue(json, WorkflowPlan.class);
        assertEquals(2, workflowPlan.getActions().size());

        // Check the first action
        assertTrue(workflowPlan.getActions().containsKey("first_action"));
        Action firstAction = workflowPlan.getActions().get("first_action");
        assertInstanceOf(JavaFunction.class, firstAction.getExec());
        assertEquals(List.of(InputEvent.class.getName()), firstAction.getListenEventTypes());

        // Check the second action
        assertTrue(workflowPlan.getActions().containsKey("second_action"));
        Action secondAction = workflowPlan.getActions().get("second_action");
        assertInstanceOf(JavaFunction.class, secondAction.getExec());
        assertEquals(
                List.of(InputEvent.class.getName(), MyEvent.class.getName()),
                secondAction.getListenEventTypes());

        // Check event trigger actions
        assertEquals(2, workflowPlan.getEventTriggerActions().size());
        assertTrue(workflowPlan.getEventTriggerActions().containsKey(InputEvent.class.getName()));
        assertEquals(
                List.of(firstAction, secondAction),
                workflowPlan.getEventTriggerActions().get(InputEvent.class.getName()));
        assertEquals(
                List.of(secondAction),
                workflowPlan.getEventTriggerActions().get(MyEvent.class.getName()));
    }

    /**
     * Reads a JSON file from the resources directory.
     *
     * @param resourcePath the path to the resource file
     * @return the content of the file as a string
     * @throws IOException if an I/O error occurs
     */
    private String readJsonFromResource(String resourcePath) throws IOException {
        try (InputStream inputStream =
                getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

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
}
