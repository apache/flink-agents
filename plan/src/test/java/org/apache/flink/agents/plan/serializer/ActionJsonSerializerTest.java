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
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
                        new Class[] {Event.class, RunnerContext.class});

        // Create an Action
        Action action = new Action("testAction", function, List.of(InputEvent.EVENT_TYPE));

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
                json.contains("\"trigger_conditions\":["),
                "JSON should contain trigger conditions");
        assertTrue(
                json.contains("\"" + InputEvent.EVENT_TYPE + "\""),
                "JSON should contain the event type string");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.context.RunnerContext\""),
                "JSON should contain the runner context class name");
    }

    @Test
    public void testSerializePythonFunction() throws Exception {
        // Create a TestPythonFunction (which overrides checkSignature to not throw an exception)
        PythonFunction function = new PythonFunction("test_module", "test_function");

        // Create an Action
        Action action = new Action("testPythonAction", function, List.of(InputEvent.EVENT_TYPE));

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
                json.contains("\"trigger_conditions\":["),
                "JSON should contain trigger conditions");
        assertTrue(
                json.contains("\"" + InputEvent.EVENT_TYPE + "\""),
                "JSON should contain the event type string");
    }

    @Test
    public void testSerializeMultipleTriggerConditions() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {Event.class, RunnerContext.class});

        // Preserve raw type and condition entries, including whitespace and duplicates.
        List<String> triggerConditions = new ArrayList<>();
        triggerConditions.add(InputEvent.EVENT_TYPE);
        triggerConditions.add(" score > 1 ");
        triggerConditions.add(InputEvent.EVENT_TYPE);
        Action action = new Action("multiEventAction", function, triggerConditions);

        // Serialize the action to JSON
        String json = new ObjectMapper().writeValueAsString(action);

        // Verify the JSON contains the expected fields
        assertTrue(
                json.contains("\"name\":\"multiEventAction\""),
                "JSON should contain the action name");
        assertTrue(
                json.contains("\"trigger_conditions\":["),
                "JSON should contain trigger conditions");
        assertTrue(
                json.contains("\"" + InputEvent.EVENT_TYPE + "\""),
                "JSON should contain the InputEvent type string");
        assertTrue(
                json.contains("\" score > 1 \""), "JSON should contain the raw condition string");
        assertTrue(
                json.contains("\"org.apache.flink.agents.api.context.RunnerContext\""),
                "JSON should contain the runner context class name");

        // Pin the exact serialized trigger_conditions structure and order.
        Action restored = new ObjectMapper().readValue(json, Action.class);
        assertEquals(
                List.of(InputEvent.EVENT_TYPE, " score > 1 ", InputEvent.EVENT_TYPE),
                restored.getTriggerConditions());
    }

    @Test
    public void testSerializeDeserializeRoundTrip() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {Event.class, RunnerContext.class});

        // Create an Action
        Action originalAction =
                new Action("roundTripAction", function, List.of(InputEvent.EVENT_TYPE));

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
        assertEquals(Event.class, deserializedFunction.getParameterTypes()[0]);
        assertEquals(RunnerContext.class, deserializedFunction.getParameterTypes()[1]);
        assertEquals(1, deserializedAction.getTriggerConditions().size());
        assertEquals(InputEvent.EVENT_TYPE, deserializedAction.getTriggerConditions().get(0));
    }

    @Test
    public void testSerializeDeserializeConfig() throws Exception {
        // Create a JavaFunction
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {Event.class, RunnerContext.class});

        Map<String, Object> config = new HashMap<>();
        InputEvent arg0 = new InputEvent("123");
        List<String> arg1 = List.of("1", "2", "3");
        Map<String, Integer> arg2 = Map.of("k1", 1, "k2", 2);
        config.put("arg0", arg0);
        config.put("arg1", arg1);
        config.put("arg2", arg2);

        // Create an Action
        Action action = new Action("testAction", function, List.of(InputEvent.EVENT_TYPE), config);

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

    @Test
    public void testSerializeDeserializePythonConfig() throws Exception {
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {Event.class, RunnerContext.class});

        Map<String, Object> config = new HashMap<>();
        config.put(ActionJsonSerializer.CONFIG_TYPE, "python");
        config.put("list", List.of(1, 2, 3));
        config.put("nested", Map.of("k1", 1, "k2", 2));

        Action action =
                new Action("pyConfigAction", function, List.of(InputEvent.EVENT_TYPE), config);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(action);
        Action actual = mapper.readValue(json, Action.class);

        Map<String, Object> restored = actual.getConfig();
        Assertions.assertNotNull(restored);
        assertEquals("python", restored.get(ActionJsonSerializer.CONFIG_TYPE));
        assertEquals(List.of(1, 2, 3), restored.get("list"));
        assertEquals(Map.of("k1", 1, "k2", 2), restored.get("nested"));
    }

    @Test
    public void testSerializeDeserializeNullConfig() throws Exception {
        JavaFunction function =
                new JavaFunction(
                        "org.apache.flink.agents.plan.TestAction",
                        "legal",
                        new Class[] {Event.class, RunnerContext.class});

        Action action = new Action("nullConfigAction", function, List.of(InputEvent.EVENT_TYPE));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(action);
        assertTrue(json.contains("\"config\":null"), "JSON should contain a null config field");

        Action actual = mapper.readValue(json, Action.class);
        Assertions.assertNull(actual.getConfig());
    }
}
