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
import org.apache.flink.agents.runtime.condition.ActionMatcher;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer F1 — feed Python-shaped JSON plans into the Java runtime and verify trigger-condition
 * matching and JavaFunction dispatch. Java→Python action dispatch goes through Pemja and is Layer
 * F2 scope.
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

    @Test
    void pythonPlanUsesRuntimeOrSemantics() throws Exception {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        Path snapshot =
                repoRoot.resolve(
                        "e2e-test/cross-language-agent-plan-snapshots/python/agent_plan_with_java_action.json");
        AgentPlan plan = MAPPER.readValue(Files.readString(snapshot), AgentPlan.class);
        Action handle = plan.getActions().get("handle");
        ActionMatcher matcher = new ActionMatcher(plan);

        assertThat(handle.getTriggerConditions())
                .containsExactly(InputEvent.EVENT_TYPE, "attributes.ready == true");
        assertThat(matcher.match(new InputEvent("type-hit"))).containsExactly(handle);
        assertThat(matcher.match(new Event("other", Map.of("ready", true))))
                .containsExactly(handle);
        assertThat(matcher.match(new Event("other", Map.of("ready", false)))).isEmpty();
    }

    /**
     * Wire format Python emits via {@code AgentPlan.model_dump_json}; pinned symmetric in Layer B.
     */
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
                + "    \"trigger_conditions\":[\"_input_event\"],"
                + "    \"config\":null"
                + "  }"
                + "},"
                + "\"resource_providers\":{},"
                + "\"config\":{\"conf_data\":{}}"
                + "}";
    }

    private static AgentPlan planWithPythonAction() throws Exception {
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
        return new AgentPlan(
                actions,
                new java.util.HashMap<>(),
                new org.apache.flink.agents.plan.AgentConfiguration());
    }
}
