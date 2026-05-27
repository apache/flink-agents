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

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Layer B plan-compile tests for cross-language {@code Function} descriptors on the Java side.
 *
 * <p>Mirrors {@code test_agent_plan_cross_language.py}. Confirms:
 *
 * <ul>
 *   <li>api → plan promotion in {@link
 *       AgentPlan#toPlanFunction(org.apache.flink.agents.api.function.Function)} handles both
 *       {@code PythonFunction} (cross-language) and {@code JavaFunction} (same-language)
 *       descriptors.
 *   <li>The compiled plan serializes to the expected wire JSON (snake_case action {@code exec}
 *       block).
 *   <li>JSON round-trips back into a structurally equivalent plan.
 *   <li>Java can deserialize Python's plan snapshot referencing a cross-language Java action body.
 * </ul>
 *
 * <p>Snapshots live under {@code e2e-test/cross-language-agent-plan-snapshots/}.
 */
class AgentPlanCrossLanguageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Static handler used as a JavaFunction target — must exist on the classpath for compile. */
    public static void handle(Event event, RunnerContext ctx) {
        // No-op: plan-compile only needs the method to resolve via Class.forName / getMethod.
    }

    private static Path snapshotDir;

    @BeforeAll
    static void resolveSnapshotDir() {
        // Maven sets user.dir to the module directory; repo root is its parent.
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        snapshotDir = repoRoot.resolve("e2e-test/cross-language-agent-plan-snapshots");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static boolean regenerateRequested() {
        return Boolean.parseBoolean(System.getProperty("regenerate.snapshots", "false"));
    }

    private static org.apache.flink.agents.api.function.JavaFunction javaFunctionDescriptor() {
        return new org.apache.flink.agents.api.function.JavaFunction(
                AgentPlanCrossLanguageTest.class.getName(),
                "handle",
                List.of(Event.class.getName(), RunnerContext.class.getName()));
    }

    private static org.apache.flink.agents.api.function.PythonFunction pythonFunctionDescriptor() {
        // Use a target that exists on the Python side too — Python's `Action.__init__`
        // eagerly imports the module via `check_signature` during JSON deserialize,
        // so the cross-language snapshot must point at a real importable callable.
        // Mirror of `_dummy_action` in `test_agent_plan_cross_language.py`.
        return new org.apache.flink.agents.api.function.PythonFunction(
                "flink_agents.plan.tests.test_agent_plan_cross_language", "_dummy_action");
    }

    private static AgentPlan compileWithPythonAction() throws Exception {
        Agent agent = new Agent();
        agent.addAction(
                "handle", new String[] {InputEvent.EVENT_TYPE}, pythonFunctionDescriptor(), null);
        return new AgentPlan(agent);
    }

    private static AgentPlan compileWithJavaAction() throws Exception {
        Agent agent = new Agent();
        agent.addAction(
                "handle", new String[] {InputEvent.EVENT_TYPE}, javaFunctionDescriptor(), null);
        return new AgentPlan(agent);
    }

    // ── api → plan promotion (Java side) ───────────────────────────────────

    @Test
    void compileAgentWithJavaFunctionDescriptor() throws Exception {
        AgentPlan plan = compileWithJavaAction();

        Action action = plan.getActions().get("handle");
        assertThat(action).isNotNull();
        assertThat(action.getExec()).isInstanceOf(JavaFunction.class);

        JavaFunction planFn = (JavaFunction) action.getExec();
        assertThat(planFn.getQualName()).isEqualTo(AgentPlanCrossLanguageTest.class.getName());
        assertThat(planFn.getMethodName()).isEqualTo("handle");
        assertThat(planFn.getParameterTypes()).containsExactly(Event.class, RunnerContext.class);
    }

    @Test
    void compileAgentWithPythonFunctionDescriptor() throws Exception {
        AgentPlan plan = compileWithPythonAction();

        Action action = plan.getActions().get("handle");
        assertThat(action).isNotNull();
        assertThat(action.getExec()).isInstanceOf(PythonFunction.class);

        PythonFunction planFn = (PythonFunction) action.getExec();
        assertThat(planFn.getModule())
                .isEqualTo("flink_agents.plan.tests.test_agent_plan_cross_language");
        assertThat(planFn.getQualName()).isEqualTo("_dummy_action");
    }

    @Test
    void compileWithJavaFunctionRequiresClassOnClasspath() {
        // Java plan-compile resolves FQNs eagerly (Class.forName), so an unknown class must fail
        // here, not later at dispatch.
        Agent agent = new Agent();
        org.apache.flink.agents.api.function.JavaFunction fake =
                new org.apache.flink.agents.api.function.JavaFunction(
                        "com.does.not.Exist", "ghost", List.of("java.lang.String"));
        agent.addAction("act", new String[] {InputEvent.EVENT_TYPE}, fake, null);

        Throwable thrown = null;
        try {
            new AgentPlan(agent);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown)
                .as("Java plan-compile should reject unresolvable JavaFunction class.")
                .isNotNull();
    }

    // ── Plan JSON shape (Java side) ────────────────────────────────────────

    @Test
    void javaPlanWithJavaActionHasExpectedExecShape() throws Exception {
        AgentPlan plan = compileWithJavaAction();
        JsonNode parsed = MAPPER.readTree(MAPPER.writeValueAsString(plan));
        JsonNode execBlock = parsed.get("actions").get("handle").get("exec");

        assertThat(execBlock.get("func_type").asText()).isEqualTo("JavaFunction");
        assertThat(execBlock.get("qualname").asText())
                .isEqualTo(AgentPlanCrossLanguageTest.class.getName());
        assertThat(execBlock.get("method_name").asText()).isEqualTo("handle");
        JsonNode params = execBlock.get("parameter_types");
        assertThat(params.isArray()).isTrue();
        assertThat(params.get(0).asText()).isEqualTo(Event.class.getName());
        assertThat(params.get(1).asText()).isEqualTo(RunnerContext.class.getName());
    }

    @Test
    void javaPlanWithPythonActionHasExpectedExecShape() throws Exception {
        AgentPlan plan = compileWithPythonAction();
        JsonNode parsed = MAPPER.readTree(MAPPER.writeValueAsString(plan));
        JsonNode execBlock = parsed.get("actions").get("handle").get("exec");

        assertThat(execBlock.get("func_type").asText()).isEqualTo("PythonFunction");
        assertThat(execBlock.get("module").asText())
                .isEqualTo("flink_agents.plan.tests.test_agent_plan_cross_language");
        assertThat(execBlock.get("qualname").asText()).isEqualTo("_dummy_action");
    }

    // ── Plan JSON round-trip (Java side) ───────────────────────────────────

    @Test
    void javaPlanWithJavaActionRoundTripsThroughJson() throws Exception {
        AgentPlan plan = compileWithJavaAction();
        String json = MAPPER.writeValueAsString(plan);
        AgentPlan restored = MAPPER.readValue(json, AgentPlan.class);

        Action action = restored.getActions().get("handle");
        assertThat(action.getExec()).isInstanceOf(JavaFunction.class);
        JavaFunction jf = (JavaFunction) action.getExec();
        assertThat(jf.getQualName()).isEqualTo(AgentPlanCrossLanguageTest.class.getName());
        assertThat(jf.getMethodName()).isEqualTo("handle");
    }

    @Test
    void javaPlanWithPythonActionRoundTripsThroughJson() throws Exception {
        AgentPlan plan = compileWithPythonAction();
        String json = MAPPER.writeValueAsString(plan);
        AgentPlan restored = MAPPER.readValue(json, AgentPlan.class);

        Action action = restored.getActions().get("handle");
        assertThat(action.getExec()).isInstanceOf(PythonFunction.class);
        PythonFunction pf = (PythonFunction) action.getExec();
        assertThat(pf.getModule())
                .isEqualTo("flink_agents.plan.tests.test_agent_plan_cross_language");
        assertThat(pf.getQualName()).isEqualTo("_dummy_action");
    }

    // ── Cross-language snapshot (Java writes / Python reads) ───────────────

    @Test
    void regenerateJavaPlanWithPythonActionSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        AgentPlan plan = compileWithPythonAction();
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan);

        Path target = snapshotDir.resolve("java/agent_plan_with_python_action.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json + "\n");
    }

    @Test
    void javaPlanWithPythonActionSnapshotIsStable() throws Exception {
        Path committed = snapshotDir.resolve("java/agent_plan_with_python_action.json");
        assumeTrue(
                Files.exists(committed),
                "Java plan snapshot not committed yet; run with -Dregenerate.snapshots=true.");

        AgentPlan plan = compileWithPythonAction();
        JsonNode actual = MAPPER.readTree(MAPPER.writeValueAsString(plan));
        JsonNode expected = MAPPER.readTree(Files.readString(committed));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void javaCanDeserializePythonPlanWithJavaAction() throws Exception {
        Path snapshot = snapshotDir.resolve("python/agent_plan_with_java_action.json");
        assumeTrue(
                Files.exists(snapshot),
                "Python plan snapshot not present; run Python regen first.");

        String json = Files.readString(snapshot);
        AgentPlan restored = MAPPER.readValue(json, AgentPlan.class);

        Action handle = restored.getActions().get("handle");
        assertThat(handle).isNotNull();
        assertThat(handle.getExec()).isInstanceOf(JavaFunction.class);
        JavaFunction jf = (JavaFunction) handle.getExec();
        assertThat(jf.getQualName()).isEqualTo("com.example.Handlers");
        assertThat(jf.getMethodName()).isEqualTo("handle");
    }
}
