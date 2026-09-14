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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.lifecycle.ComponentExecutionListener;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Tests for execution reports fanned out from {@link RunnerContextImpl} to its listeners. */
class RunnerContextImplExecutionReporterTest {

    @Test
    void reportsFanOutToComponentExecutionListeners() throws Exception {
        RecordingComponentListener listener = new RecordingComponentListener();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(listener));

        runnerContext.reportExecutionStarted(
                ExecutionReporter.EntityTypes.LLM, "model-a", Map.of("temperature", 0.7));
        runnerContext.reportExecutionSucceeded(
                ExecutionReporter.EntityTypes.LLM, "model-a", Map.of("temperature", 0.7));

        assertThat(listener.started).hasSize(1);
        assertThat(listener.started.get(0))
                .containsExactly(
                        ExecutionReporter.EntityTypes.LLM, "model-a", Map.of("temperature", 0.7));
        assertThat(listener.succeeded).hasSize(1);
        assertThat(listener.succeeded.get(0))
                .containsExactly(
                        ExecutionReporter.EntityTypes.LLM, "model-a", Map.of("temperature", 0.7));
    }

    @Test
    void failedReportResolvesRootCauseTypeAndMessage() throws Exception {
        RecordingComponentListener listener = new RecordingComponentListener();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(listener));

        runnerContext.reportExecutionFailed(
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of("toolCallId", "call-1"),
                new RuntimeException(new IllegalStateException("backend down")),
                ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);

        assertThat(listener.failed).hasSize(1);
        RecordedFailure failure = listener.failed.get(0);
        assertThat(failure.entityType).isEqualTo(ExecutionReporter.EntityTypes.TOOL);
        assertThat(failure.entityName).isEqualTo("search");
        assertThat(failure.entityMetadata).containsEntry("toolCallId", "call-1");
        assertThat(failure.errorType).isEqualTo(IllegalStateException.class.getName());
        assertThat(failure.errorMessage).isEqualTo("backend down");
        assertThat(failure.problemCategory)
                .isEqualTo(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
    }

    @Test
    void throwingListenerNeverFailsTheReportingCall() throws Exception {
        RecordingComponentListener receiver = new RecordingComponentListener();
        ComponentExecutionListener thrower =
                (entityType, entityName, entityMetadata, event) -> {
                    throw new IllegalStateException("listener boom");
                };
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(thrower, receiver));

        assertThatCode(
                        () -> {
                            runnerContext.reportExecutionStarted(
                                    ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());
                            runnerContext.reportExecutionSucceeded(
                                    ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());
                            runnerContext.reportExecutionFailed(
                                    ExecutionReporter.EntityTypes.LLM,
                                    "model-a",
                                    Map.of(),
                                    new IllegalStateException("call failed"),
                                    null);
                        })
                .doesNotThrowAnyException();

        // The throwing listener is skipped; the remaining listener still receives every report.
        assertThat(receiver.started).hasSize(1);
        assertThat(receiver.succeeded).hasSize(1);
        assertThat(receiver.failed).hasSize(1);
    }

    @Test
    void reportingWithoutListenersIsANoOp() throws Exception {
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        runnerContext.switchActionContext(
                "chat_model_action", null, new ArrayList<>(), "business-key", "obs-1", false, null);

        assertThatCode(
                        () -> {
                            runnerContext.reportExecutionStarted(
                                    ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());
                            runnerContext.reportExecutionSucceeded(
                                    ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());
                        })
                .doesNotThrowAnyException();
    }

    @Test
    void pythonReporterBridgePreservesMetadataAndPythonErrorFields() throws Exception {
        RecordingComponentListener listener = new RecordingComponentListener();
        PythonRunnerContextImpl runnerContext =
                new PythonRunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        runnerContext.switchActionContext(
                "tool_call_action",
                null,
                new ArrayList<>(),
                "business-key",
                "obs-1",
                false,
                List.of(listener));

        String metadata = "{\"toolCallId\":\"call-1\",\"toolType\":\"function\"}";
        runnerContext.reportExecutionStartedJson(
                ExecutionReporter.EntityTypes.TOOL, "search", metadata);
        runnerContext.reportExecutionFailedJson(
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                metadata,
                "builtins.ValueError",
                "bad response",
                ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);

        assertThat(listener.started).hasSize(1);
        assertThat(listener.started.get(0).get(2))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("toolCallId", "call-1")
                .containsEntry("toolType", "function");

        assertThat(listener.failed).hasSize(1);
        RecordedFailure failure = listener.failed.get(0);
        // Python reports cross the bridge as strings and must reach listeners verbatim.
        assertThat(failure.errorType).isEqualTo("builtins.ValueError");
        assertThat(failure.errorMessage).isEqualTo("bad response");
        assertThat(failure.problemCategory)
                .isEqualTo(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
    }

    private static void switchToChatModelAction(
            RunnerContextImpl runnerContext, List<ComponentExecutionListener> listeners) {
        runnerContext.switchActionContext(
                "chat_model_action",
                null,
                new ArrayList<>(),
                "business-key",
                "obs-1",
                false,
                listeners);
    }

    private static AgentPlan emptyAgentPlan() {
        return new AgentPlan(new HashMap<>(), new HashMap<>());
    }

    /** Records the raw arguments of every component report it receives. */
    private static final class RecordingComponentListener implements ComponentExecutionListener {
        private final List<List<Object>> started = new ArrayList<>();
        private final List<List<Object>> succeeded = new ArrayList<>();
        private final List<RecordedFailure> failed = new ArrayList<>();

        @Override
        public void onComponentExecution(
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                Event event) {
            switch (event.getType()) {
                case ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE:
                    started.add(List.of(entityType, entityName, entityMetadata));
                    break;
                case ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE:
                    succeeded.add(List.of(entityType, entityName, entityMetadata));
                    break;
                case ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE:
                    failed.add(new RecordedFailure(entityType, entityName, entityMetadata, event));
                    break;
                default:
                    throw new AssertionError("Unexpected event type " + event.getType());
            }
        }
    }

    private static final class RecordedFailure {
        private final String entityType;
        private final String entityName;
        private final Map<String, Object> entityMetadata;
        private final String errorType;
        @Nullable private final String errorMessage;
        @Nullable private final String problemCategory;

        private RecordedFailure(
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                Event event) {
            this.entityType = entityType;
            this.entityName = entityName;
            this.entityMetadata = entityMetadata;
            this.errorType = (String) event.getAttr("errorType");
            this.errorMessage = (String) event.getAttr("errorMessage");
            this.problemCategory =
                    (String) event.getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE);
        }
    }
}
