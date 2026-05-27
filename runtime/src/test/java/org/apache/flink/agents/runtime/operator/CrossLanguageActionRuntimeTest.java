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
package org.apache.flink.agents.runtime.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer F1 — feed a Python-shaped JSON plan into the operator harness and confirm a JavaFunction
 * action body runs. Java→Python action dispatch goes through Pemja and is Layer F2 scope.
 */
public class CrossLanguageActionRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Public nested class so reflection can invoke {@code handleInput} without access errors. */
    public static class Handlers {
        public static void handleInput(Event event, RunnerContext context) {
            Long value = (Long) InputEvent.fromEvent(event).getInput();
            context.sendEvent(new OutputEvent(value * 2));
        }
    }

    @Test
    void operatorRunsJavaActionFromPythonShapedPlanJson() throws Exception {
        String planJson = pythonShapedPlanJson();

        AgentPlan plan = MAPPER.readValue(planJson, AgentPlan.class);

        Action handle = plan.getActions().get("handle");
        assertThat(handle).isNotNull();
        assertThat(handle.getExec()).isInstanceOf(JavaFunction.class);
        JavaFunction execFn = (JavaFunction) handle.getExec();
        assertThat(execFn.getQualName()).isEqualTo(Handlers.class.getName());
        assertThat(execFn.getMethodName()).isEqualTo("handleInput");

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(3L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(6L);
        }
    }

    @Test
    void pythonFunctionPlanDeserializesAndIsRecognizedByOperatorFactory() throws Exception {
        // Pins deserialize + factory-accepts-plan without opening the operator (Pemja libpython
        // load is Layer F2).
        AgentPlan plan = planWithPythonAction();

        Action handle = plan.getActions().get("py_handle");
        assertThat(handle).isNotNull();
        assertThat(handle.getExec()).isInstanceOf(PythonFunction.class);

        ActionExecutionOperatorFactory factory = new ActionExecutionOperatorFactory(plan, true);
        assertThat(factory).isNotNull();
    }

    /** Wire format Python emits via {@code AgentPlan.model_dump_json}; pinned symmetric in Layer B. */
    private static String pythonShapedPlanJson() {
        String qualName = Handlers.class.getName();
        return "{"
                + "\"actions\":{"
                + "  \"handle\":{"
                + "    \"name\":\"handle\","
                + "    \"exec\":{"
                + "      \"func_type\":\"JavaFunction\","
                + "      \"qualname\":\""
                + qualName
                + "\","
                + "      \"method_name\":\"handleInput\","
                + "      \"parameter_types\":["
                + "        \"org.apache.flink.agents.api.Event\","
                + "        \"org.apache.flink.agents.api.context.RunnerContext\""
                + "      ]"
                + "    },"
                + "    \"listen_event_types\":[\"_input_event\"],"
                + "    \"config\":null"
                + "  }"
                + "},"
                + "\"actions_by_event\":{"
                + "  \"_input_event\":[\"handle\"]"
                + "},"
                + "\"resource_providers\":{},"
                + "\"config\":{\"conf_data\":{}}"
                + "}";
    }

    private static AgentPlan planWithPythonAction() throws Exception {
        java.util.Map<String, List<Action>> actionsByEvent = new java.util.HashMap<>();
        java.util.Map<String, Action> actions = new java.util.HashMap<>();

        PythonFunction pythonFn =
                new PythonFunction(
                        "flink_agents.plan.tests.test_agent_plan_cross_language", "_dummy_action");
        Action act =
                new Action(
                        "py_handle",
                        pythonFn,
                        java.util.Collections.singletonList(InputEvent.EVENT_TYPE));
        actions.put(act.getName(), act);
        actionsByEvent.put(InputEvent.EVENT_TYPE, java.util.Collections.singletonList(act));

        return new AgentPlan(
                actions,
                actionsByEvent,
                new java.util.HashMap<>(),
                new org.apache.flink.agents.plan.AgentConfiguration());
    }
}
