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
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test for {@link ActionJsonSerializer}. */
public class ActionJsonSerializerTest {
    @Test
    public void testSerializeJavaFunction() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create an Action
        Action action = new Action("testAction", function, List.of(InputEvent.class.getName()));

        // Serialize the action to JSON
        String json = new ObjectMapper().writeValueAsString(action);

        // Verify the JSON contains the expected fields
        assertTrue(json.contains("\"name\":\"testAction\""), "JSON should contain the action name");
        assertTrue(json.contains("\"exec\":{"), "JSON should contain the exec field");
        assertTrue(
                json.contains("\"func_type\":\"JavaFunction\""),
                "JSON should contain the function type");
        assertTrue(
                json.contains("\"qualname\":\"org.apache.flink.agents.plan.TestAction\""),
                "JSON should contain the function's qualified name");
        assertTrue(
                json.contains("\"method_name\":\"legal\""), "JSON should contain the method name");
        assertTrue(
                json.contains("\"listen_event_types\":["),
                "JSON should contain the listen event types");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.InputEvent\""),
                "JSON should contain the event type class name");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.context.RunnerContext\""),
                "JSON should contain the runner context class name");
    }

    @Test
    public void testSerializePythonFunction() throws Exception {
        // Create a TestPythonFunction (which overrides checkSignature to not throw an exception)
        PythonFunction function = new PythonFunction("test_module", "test_function");

        // Create an Action
        Action action =
                new Action("testPythonAction", function, List.of(InputEvent.class.getName()));

        // Serialize the action to JSON
        String json = new ObjectMapper().writeValueAsString(action);

        // Verify the JSON contains the expected fields
        assertTrue(
                json.contains("\"name\":\"testPythonAction\""),
                "JSON should contain the action name");
        assertTrue(json.contains("\"exec\":{"), "JSON should contain the exec field");
        assertTrue(
                json.contains("\"func_type\":\"PythonFunction\""),
                "JSON should contain the function type");
        assertTrue(
                json.contains("\"module\":\"test_module\""), "JSON should contain the module name");
        assertTrue(
                json.contains("\"qualname\":\"test_function\""),
                "JSON should contain the qualified name");
        assertTrue(
                json.contains("\"listen_event_types\":["),
                "JSON should contain the listen event types");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.InputEvent\""),
                "JSON should contain the event type class name");
    }

    @Test
    public void testSerializeMultipleEventTypes() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create an Action with multiple event types
        List<String> eventTypes = new ArrayList<>();
        eventTypes.add(InputEvent.class.getName());
        eventTypes.add(OutputEvent.class.getName());
        Action action = new Action("multiEventAction", function, eventTypes);

        // Serialize the action to JSON
        String json = new ObjectMapper().writeValueAsString(action);

        // Verify the JSON contains the expected fields
        assertTrue(
                json.contains("\"name\":\"multiEventAction\""),
                "JSON should contain the action name");
        assertTrue(
                json.contains("\"listen_event_types\":["),
                "JSON should contain the listen event types");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.InputEvent\""),
                "JSON should contain the InputEvent class name");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.OutputEvent\""),
                "JSON should contain the OutputEvent class name");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.context.RunnerContext\""),
                "JSON should contain the runner context class name");
    }

    @Test
    public void testSerializeEmptyEventTypes() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create an Action with an empty event types list
        Action action = new Action("emptyEventsAction", function, Collections.emptyList());

        // Serialize the action to JSON
        String json = new ObjectMapper().writeValueAsString(action);

        // Verify the JSON contains the expected fields
        assertTrue(
                json.contains("\"name\":\"emptyEventsAction\""),
                "JSON should contain the action name");
        assertTrue(
                json.contains("\"listen_event_types\":[]"),
                "JSON should contain an empty listen event types array");
    }

    @Test
    public void testSerializeDeserializeRoundTrip() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        // Create an Action
        Action originalAction =
                new Action("roundTripAction", function, List.of(InputEvent.class.getName()));

        // Serialize the action to JSON
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(originalAction);

        // Deserialize the JSON back to an Action
        Action deserializedAction = mapper.readValue(json, Action.class);

        // Verify the deserialized Action matches the original
        assertEquals("roundTripAction", deserializedAction.getName());
        assertInstanceOf(JavaFunction.class, deserializedAction.getExec());
        JavaFunction deserializedFunction = (JavaFunction) deserializedAction.getExec();
        assertEquals("org.apache.flink.agents.plan.TestAction", deserializedFunction.getQualName());
        assertEquals("legal", deserializedFunction.getMethodName());
        assertEquals(2, deserializedFunction.getParameterTypes().length);
        assertEquals(InputEvent.class, deserializedFunction.getParameterTypes()[0]);
        assertEquals(RunnerContext.class, deserializedFunction.getParameterTypes()[1]);
        assertEquals(1, deserializedAction.getListenEventTypes().size());
        assertEquals(InputEvent.class.getName(), deserializedAction.getListenEventTypes().get(0));
    }

    @Test
    public void testSerializeDeserializeConfig() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {InputEvent.class, RunnerContext.class});

        Map<String, Object> config = new HashMap<>();
        InputEvent arg0 = new InputEvent("123");
        List<String> arg1 = List.of("1", "2", "3");
        Map<String, Integer> arg2 = Map.of("k1", 1, "k2", 2);
        config.put("arg0", arg0);
        config.put("arg1", arg1);
        config.put("arg2", arg2);

        // Create an Action
        Action action =
                new Action("testAction", function, List.of(InputEvent.class.getName()), config);

        // Serialize the action to JSON
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(action);

        Action actual = mapper.readValue(json, Action.class);
        Assertions.assertNotNull(actual.getConfig());
        Map<String, Object> deserializeConfig = actual.getConfig();
        Assertions.assertEquals("123", ((InputEvent) deserializeConfig.get("arg0")).getInput());
        Assertions.assertEquals(arg1, deserializeConfig.get("arg1"));
        Assertions.assertEquals(arg2, deserializeConfig.get("arg2"));
    }
}
