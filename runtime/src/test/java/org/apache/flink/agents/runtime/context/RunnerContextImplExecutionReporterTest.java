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
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.trace.ReportedExecutionKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for execution reports emitted from {@link RunnerContextImpl}. */
class RunnerContextImplExecutionReporterTest {

    @Test
    void reportedExecutionReusesChildTraceContextBetweenStartAndFinish() throws Exception {
        List<RecordedReport> reports = new ArrayList<>();
        RunnerContextImpl runnerContext = new RunnerContextImpl(null, () -> {}, null, null, "job");
        ExecutionTraceContext actionTraceContext =
                ExecutionTraceContext.forInputRun("business-key", "agent")
                        .childExecution("action", "chat_model_action");
        runnerContext.setExecutionEventSink(
                (event, context) -> reports.add(new RecordedReport(event, context)));
        runnerContext.switchActionContext(
                "chat_model_action", null, "business-key", actionTraceContext, new HashMap<>());

        runnerContext.reportExecutionStarted(
                ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());
        runnerContext.reportExecutionSucceeded(
                ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());

        assertThat(reports).hasSize(2);
        RecordedReport started = reports.get(0);
        RecordedReport finished = reports.get(1);

        assertThat(started.event.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE);
        assertThat(finished.event.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE);
        assertThat(started.status()).isEqualTo(ExecutionLifecycleEvents.STATUS_STARTED);
        assertThat(finished.status()).isEqualTo(ExecutionLifecycleEvents.STATUS_SUCCESS);

        assertThat(started.traceContext().getExecutionId()).isNotBlank();
        assertThat(finished.traceContext().getExecutionId())
                .isEqualTo(started.traceContext().getExecutionId());
        assertThat(started.traceContext().getParentExecutionId())
                .isEqualTo(actionTraceContext.getExecutionId());
        assertThat(started.traceContext().getEntityType())
                .isEqualTo(ExecutionReporter.EntityTypes.LLM);
        assertThat(started.traceContext().getEntityName()).isEqualTo("model-a");
    }

    @Test
    void reportedExecutionStateFollowsActionContextAcrossSwitches() throws Exception {
        List<RecordedReport> reports = new ArrayList<>();
        RunnerContextImpl runnerContext = new RunnerContextImpl(null, () -> {}, null, null, "job");
        runnerContext.setExecutionEventSink(
                (event, context) -> reports.add(new RecordedReport(event, context)));

        ExecutionTraceContext actionA =
                ExecutionTraceContext.forInputRun("business-key", "agent")
                        .childExecution("action", "chat_model_action");
        ExecutionTraceContext actionB =
                ExecutionTraceContext.forInputRun("business-key", "agent")
                        .childExecution("action", "tool_call_action");
        Map<ReportedExecutionKey, ExecutionTraceContext> activeReportsA = new HashMap<>();
        Map<ReportedExecutionKey, ExecutionTraceContext> activeReportsB = new HashMap<>();

        runnerContext.switchActionContext(
                "chat_model_action", null, "business-key", actionA, activeReportsA);
        runnerContext.reportExecutionStarted(
                ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());

        runnerContext.switchActionContext(
                "tool_call_action", null, "business-key", actionB, activeReportsB);
        runnerContext.reportExecutionStarted(
                ExecutionReporter.EntityTypes.TOOL, "search", Map.of("toolCallId", "call-1"));

        runnerContext.switchActionContext(
                "chat_model_action", null, "business-key", actionA, activeReportsA);
        runnerContext.reportExecutionSucceeded(
                ExecutionReporter.EntityTypes.LLM, "model-a", Map.of());

        assertThat(reports).hasSize(3);
        RecordedReport actionAStarted = reports.get(0);
        RecordedReport actionBStarted = reports.get(1);
        RecordedReport actionAFinished = reports.get(2);

        assertThat(actionAFinished.traceContext().getExecutionId())
                .isEqualTo(actionAStarted.traceContext().getExecutionId());
        assertThat(actionAFinished.traceContext().getParentExecutionId())
                .isEqualTo(actionA.getExecutionId());
        assertThat(actionBStarted.traceContext().getExecutionId())
                .isNotEqualTo(actionAStarted.traceContext().getExecutionId());
    }

    @Test
    void pythonReporterBridgePreservesMetadataAndPythonErrorFields() throws Exception {
        List<RecordedReport> reports = new ArrayList<>();
        PythonRunnerContextImpl runnerContext =
                new PythonRunnerContextImpl(null, () -> {}, null, null, "job");
        ExecutionTraceContext actionTraceContext =
                ExecutionTraceContext.forInputRun("business-key", "agent")
                        .childExecution("action", "tool_call_action");
        runnerContext.setExecutionEventSink(
                (event, context) -> reports.add(new RecordedReport(event, context)));
        runnerContext.switchActionContext(
                "tool_call_action", null, "business-key", actionTraceContext, new HashMap<>());

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

        assertThat(reports).hasSize(2);
        RecordedReport started = reports.get(0);
        RecordedReport failed = reports.get(1);

        assertThat(failed.traceContext().getExecutionId())
                .isEqualTo(started.traceContext().getExecutionId());
        assertThat(failed.traceContext().getEntityMetadata())
                .containsEntry("toolCallId", "call-1")
                .containsEntry("toolType", "function");
        assertThat(failed.event.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE);
        assertThat(failed.event.getAttr("errorType")).isEqualTo("builtins.ValueError");
        assertThat(failed.event.getAttr("errorMessage")).isEqualTo("bad response");
        assertThat(failed.event.getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE))
                .isEqualTo(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
    }

    private static class RecordedReport {
        private final Event event;
        private final ExecutionTraceContext traceContext;

        private RecordedReport(Event event, ExecutionTraceContext traceContext) {
            this.event = event;
            this.traceContext = traceContext;
        }

        private ExecutionTraceContext traceContext() {
            return traceContext;
        }

        private String status() {
            return (String) event.getAttr(ExecutionLifecycleEvents.STATUS_ATTRIBUTE);
        }
    }
}
