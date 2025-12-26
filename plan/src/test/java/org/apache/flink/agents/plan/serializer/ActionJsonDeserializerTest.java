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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Test for {@link ActionJsonDeserializer}. */
public class ActionJsonDeserializerTest {

    @Test
    public void testDeserializeJavaFunction() throws Exception {
        // Read JSON for an Action with JavaFunction from resource file
        String json = Utils.readJsonFromResource("actions/action_java_function.json");

        // Deserialize the JSON to an Action
        ObjectMapper mapper = new ObjectMapper();
        Action action = mapper.readValue(json, Action.class);

        // Verify the deserialized Action
        assertEquals("testAction", action.getName());
        assertInstanceOf(JavaFunction.class, action.getExec());
        JavaFunction javaFunction = (JavaFunction) action.getExec();
        assertEquals("org.apache.flink.agents.plan.TestAction", javaFunction.getQualName());
        assertEquals("legal", javaFunction.getMethodName());
        assertEquals(2, javaFunction.getParameterTypes().length);
        assertEquals(InputEvent.class, javaFunction.getParameterTypes()[0]);
        assertEquals(RunnerContext.class, javaFunction.getParameterTypes()[1]);
        assertEquals(1, action.getListenEventTypes().size());
        assertEquals(InputEvent.class.getName(), action.getListenEventTypes().get(0));
    }

    @Test
    public void testDeserializePythonFunction() throws IOException {
        // Read JSON for an Action with PythonFunction from resource file
        String json = Utils.readJsonFromResource("actions/action_python_function.json");

        // Deserialize the JSON to an Action
        ObjectMapper mapper = new ObjectMapper();
        Action action = mapper.readValue(json, Action.class);

        // Verify the deserialized Action
        assertEquals("testPythonAction", action.getName());
        assertInstanceOf(PythonFunction.class, action.getExec());
        PythonFunction pythonFunction = (PythonFunction) action.getExec();
        assertEquals("test_module", pythonFunction.getModule());
        assertEquals("test_function", pythonFunction.getQualName());
        assertEquals(1, action.getListenEventTypes().size());
        assertEquals(InputEvent.class.getName(), action.getListenEventTypes().get(0));
    }

    @Test
    public void testDeserializeInvalidFunctionType() throws IOException {
        // Read JSON with an invalid function type from resource file
        String json = Utils.readJsonFromResource("actions/action_invalid_function_type.json");

        // Attempt to deserialize the JSON
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(IOException.class, () -> mapper.readValue(json, Action.class));
    }

    @Test
    public void testDeserializeMissingFields() throws IOException {
        // Read JSON with missing fields from resource file
        String json = Utils.readJsonFromResource("actions/action_missing_fields.json");

        // Attempt to deserialize the JSON
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.readValue(json, Action.class));
    }

    @Test
    public void testDeserializeInvalidEventType() throws IOException {
        // Read JSON with an invalid event type from resource file
        String json = Utils.readJsonFromResource("actions/action_invalid_event_type.json");

        // Attempt to deserialize the JSON
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(RuntimeException.class, () -> mapper.readValue(json, Action.class));
    }
}
