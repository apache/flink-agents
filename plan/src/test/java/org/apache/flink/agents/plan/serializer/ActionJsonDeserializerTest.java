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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.condition.TriggerCondition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(Event.class, javaFunction.getParameterTypes()[0]);
        assertEquals(RunnerContext.class, javaFunction.getParameterTypes()[1]);
        assertEquals(1, action.getTriggerConditions().size());
        assertEquals(InputEvent.EVENT_TYPE, action.getTriggerConditions().get(0));
        assertThat(action.getClassifiedTriggerConditions())
                .containsExactly(TriggerCondition.classify(InputEvent.EVENT_TYPE));
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
        assertEquals(1, action.getTriggerConditions().size());
        assertEquals(InputEvent.EVENT_TYPE, action.getTriggerConditions().get(0));
        assertThat(action.getClassifiedTriggerConditions())
                .containsExactly(TriggerCondition.classify(InputEvent.EVENT_TYPE));
    }

    @Test
    public void pythonJsonUsesPlanValidation() throws IOException {
        String validJson =
                "{\"name\":\"pythonCondition\","
                        + "\"exec\":{\"func_type\":\"PythonFunction\","
                        + "\"module\":\"test_module\",\"qualname\":\"test_function\"},"
                        + "\"trigger_conditions\":[\" type == '_input_event' \"],"
                        + "\"config\":null}";
        Action action = new ObjectMapper().readValue(validJson, Action.class);
        assertThat(action.getClassifiedTriggerConditions())
                .containsExactly(TriggerCondition.classify("type == '_input_event'"));

        String invalidJson = validJson.replace(" type == '_input_event' ", "type ==");
        assertThatThrownBy(() -> new ObjectMapper().readValue(invalidJson, Action.class))
                .hasMessageContaining("action 'pythonCondition'")
                .hasMessageContaining("trigger condition #1")
                .hasMessageContaining("type ==");
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

        // Attempt to deserialize the JSON: a JavaFunction exec missing 'qualname' fails fast
        // in deserializeJavaFunction before any trigger-condition parsing.
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(NullPointerException.class, () -> mapper.readValue(json, Action.class));
    }

    @Test
    public void deserializesDottedCondition() throws IOException {
        String json = Utils.readJsonFromResource("actions/action_unquoted_dotted_event_type.json");

        ObjectMapper mapper = new ObjectMapper();
        Action action = mapper.readValue(json, Action.class);
        assertThat(action.getTriggerConditions())
                .containsExactly("org.apache.flink.agents.api.NonExistentEvent");
    }

    @Test
    public void deserializesQuotedEventType() throws Exception {
        String json =
                "{\"name\": \"testAction\","
                        + " \"exec\": {\"func_type\": \"JavaFunction\","
                        + " \"qualname\": \"org.apache.flink.agents.plan.TestAction\","
                        + " \"method_name\": \"legal\","
                        + " \"parameter_types\": [\"org.apache.flink.agents.api.Event\","
                        + " \"org.apache.flink.agents.api.context.RunnerContext\"]},"
                        + " \"trigger_conditions\": [\"'com.example.order-created'\"],"
                        + " \"config\": null}";

        ObjectMapper mapper = new ObjectMapper();
        Action action = mapper.readValue(json, Action.class);

        assertEquals(1, action.getTriggerConditions().size());
        assertEquals("'com.example.order-created'", action.getTriggerConditions().get(0));
    }

    @Test
    public void rejectsLegacyListenTypesField() {
        String json =
                "{\"name\": \"legacyAction\","
                        + " \"exec\": {\"func_type\": \"JavaFunction\","
                        + " \"qualname\": \"org.apache.flink.agents.plan.TestAction\","
                        + " \"method_name\": \"legal\","
                        + " \"parameter_types\": [\"org.apache.flink.agents.api.Event\","
                        + " \"org.apache.flink.agents.api.context.RunnerContext\"]},"
                        + " \"listen_event_types\": [\"_input_event\"],"
                        + " \"config\": null}";

        assertThatThrownBy(() -> new ObjectMapper().readValue(json, Action.class))
                .hasMessageContaining("trigger_conditions");
    }

    @Test
    public void testDeserializePythonConfigPreservesPrimitiveTypes() throws IOException {
        JsonNode node =
                new ObjectMapper()
                        .readTree(
                                "{\"timeout_sec\": 30,"
                                        + " \"big\": 10000000000,"
                                        + " \"enabled\": true,"
                                        + " \"rate\": 1.5,"
                                        + " \"label\": \"fast\","
                                        + " \"extra\": null}");

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                (Map<String, Object>) ActionJsonDeserializer.deserializePythonConfig(node);

        assertThat(result)
                .containsEntry("timeout_sec", 30)
                .containsEntry("big", 10_000_000_000L)
                .containsEntry("enabled", true)
                .containsEntry("rate", 1.5)
                .containsEntry("label", "fast")
                .containsKey("extra");
        assertNull(result.get("extra"));
    }
}
