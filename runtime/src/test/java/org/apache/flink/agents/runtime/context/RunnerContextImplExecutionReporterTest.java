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

import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.context.RunnerContextImpl.ExecutionReportingContext;
import org.apache.flink.agents.runtime.lifecycle.ComponentExecutionListener;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Tests for execution reports fanned out from {@link RunnerContextImpl} to its listeners. */
class RunnerContextImplExecutionReporterTest {

    private static final String TIMESTAMP = "2026-01-01T00:00:00.001Z";

    @Test
    void repeatedAndOutOfOrderReportsKeepIdentityWithoutSynthesizingObservations()
            throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(records::add));

        // A terminal report does not need an earlier creation or start report.
        runnerContext.reportExecutionSucceededAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), TIMESTAMP);
        runnerContext.reportExecutionCreated(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        runnerContext.reportExecutionCreated(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        runnerContext.reportExecutionStartedAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), TIMESTAMP);
        runnerContext.reportExecutionStartedAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), TIMESTAMP);
        runnerContext.reportExecutionSucceededAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), TIMESTAMP);
        runnerContext.reportExecutionFailedAt(
                "call-1",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of(),
                new IllegalStateException("conflicting report"),
                null,
                TIMESTAMP);

        assertThat(records)
                .extracting(TraceRecord::getStatus)
                .containsExactly(
                        "success", "created", "created", "started", "started", "success", "failed");
        assertThat(records)
                .extracting(record -> record.getContext().getExecutionId())
                .containsOnly("call-1");
    }

    @Test
    void reportsRetainCallerIdentityAndContextAsMetadataChanges() throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        TraceContext actionContext = switchToChatModelAction(runnerContext, List.of(records::add));

        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("temperature", 0.7));

        runnerContext.reportExecutionCreated(
                "call-1", ExecutionReporter.EntityTypes.LLM, "model-a", metadata);
        runnerContext.reportExecutionStartedAt(
                "call-1",
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                metadata,
                "2026-01-01T00:00:00.001Z");
        metadata.put("responseModel", "resolved");
        runnerContext.reportExecutionSucceededAt(
                "call-1",
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                metadata,
                "2026-01-01T00:00:00.025Z");

        assertThat(records)
                .extracting(TraceRecord::getStatus)
                .containsExactly(
                        TraceRecord.Statuses.CREATED,
                        TraceRecord.Statuses.STARTED,
                        TraceRecord.Statuses.SUCCESS);
        assertThat(records)
                .allSatisfy(
                        record -> {
                            TraceContext context = record.getContext();
                            assertThat(context.getEntityType())
                                    .isEqualTo(ExecutionReporter.EntityTypes.LLM);
                            assertThat(context.getEntityName()).isEqualTo("model-a");
                            assertThat(context.getExecutionId()).isEqualTo("call-1");
                            assertThat(context.getParentExecutionId())
                                    .isEqualTo(actionContext.getExecutionId());
                            assertThat(context.getInputRunId())
                                    .isEqualTo(actionContext.getInputRunId());
                            assertThat(context.getBusinessKey()).isEqualTo("business-key");
                            assertThat(context.getAgentName()).isEqualTo("test-agent");
                            assertThat(record.getProblemCategory()).isNull();
                            assertThat(record.getAttributes()).isEmpty();
                        });
        assertThat(records.subList(0, 2))
                .allSatisfy(
                        record ->
                                assertThat(record.getContext().getEntityMetadata())
                                        .containsExactlyEntriesOf(Map.of("temperature", 0.7)));
        assertThat(records.get(2).getContext().getEntityMetadata())
                .containsExactlyEntriesOf(metadata);
        assertThat(records.get(1).getTimestamp()).isEqualTo("2026-01-01T00:00:00.001Z");
        assertThat(records.get(2).getTimestamp()).isEqualTo("2026-01-01T00:00:00.025Z");
    }

    @Test
    void failedReportResolvesRootCauseTypeAndMessage() throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        TraceContext actionContext = switchToChatModelAction(runnerContext, List.of(records::add));

        runnerContext.reportExecutionFailedAt(
                "call-1",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of("toolCallId", "call-1"),
                new RuntimeException(new IllegalStateException("backend down")),
                ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED,
                "2026-01-01T00:00:00.125Z");

        assertThat(records).hasSize(1);
        TraceRecord failure = records.get(0);
        assertThat(failure.getContext().getEntityType())
                .isEqualTo(ExecutionReporter.EntityTypes.TOOL);
        assertThat(failure.getContext().getEntityName()).isEqualTo("search");
        assertThat(failure.getContext().getEntityMetadata()).containsEntry("toolCallId", "call-1");
        assertThat(failure.getContext().getExecutionId()).isEqualTo("call-1");
        assertThat(failure.getContext().getParentExecutionId())
                .isEqualTo(actionContext.getExecutionId());
        assertThat(failure.getTimestamp()).isEqualTo("2026-01-01T00:00:00.125Z");
        assertThat(failure.getStatus()).isEqualTo(TraceRecord.Statuses.FAILED);
        assertThat(failure.getAttributes())
                .containsEntry("errorType", IllegalStateException.class.getName())
                .containsEntry("errorMessage", "backend down");
        assertThat(failure.getProblemCategory())
                .isEqualTo(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
    }

    @Test
    void interleavedCallsWithIdenticalMetadataKeepSeparateIdentities() throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(records::add));

        runnerContext.reportExecutionStartedAt(
                "call-a",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of("toolType", "function"),
                TIMESTAMP);
        runnerContext.reportExecutionStartedAt(
                "call-b",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of("toolType", "function"),
                TIMESTAMP);
        runnerContext.reportExecutionSucceededAt(
                "call-a",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of("toolType", "function"),
                TIMESTAMP);
        runnerContext.reportExecutionSucceededAt(
                "call-b",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of("toolType", "function"),
                TIMESTAMP);

        assertThat(records)
                .extracting(TraceRecord::getStatus)
                .containsExactly(
                        TraceRecord.Statuses.STARTED,
                        TraceRecord.Statuses.STARTED,
                        TraceRecord.Statuses.SUCCESS,
                        TraceRecord.Statuses.SUCCESS);
        assertThat(records)
                .extracting(record -> record.getContext().getExecutionId())
                .containsExactly("call-a", "call-b", "call-a", "call-b");
        assertThat(records)
                .allSatisfy(
                        record ->
                                assertThat(record.getContext().getEntityMetadata())
                                        .containsEntry("toolType", "function"));
    }

    @Test
    void invalidObservationDoesNotFailReportingOrPreventLaterReports() throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(records::add));

        assertThatCode(
                        () -> {
                            for (String invalidId : new String[] {null, "", " "}) {
                                runnerContext.reportExecutionStartedAt(
                                        invalidId,
                                        ExecutionReporter.EntityTypes.TOOL,
                                        "search",
                                        Map.of(),
                                        TIMESTAMP);
                            }
                            runnerContext.reportExecutionStartedAt(
                                    "call-1",
                                    TraceContext.EVENT_ENTITY_TYPE,
                                    "invalid-call",
                                    Map.of(),
                                    TIMESTAMP);
                            runnerContext.reportExecutionStartedAt(
                                    "call-1",
                                    ExecutionReporter.EntityTypes.TOOL,
                                    "invalid-time",
                                    Map.of(),
                                    "");
                        })
                .doesNotThrowAnyException();
        assertThat(records).isEmpty();

        runnerContext.reportExecutionStartedAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), TIMESTAMP);
        runnerContext.reportExecutionSucceededAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), "");
        runnerContext.reportExecutionFailedAt(
                "call-1",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                Map.of(),
                new IllegalStateException("call failed"),
                ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED,
                "");
        runnerContext.reportExecutionSucceededAt(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", Map.of(), TIMESTAMP);

        assertThat(records)
                .extracting(TraceRecord::getStatus)
                .containsExactly(TraceRecord.Statuses.STARTED, TraceRecord.Statuses.SUCCESS);
        assertThat(records.get(1).getContext().getExecutionId())
                .isEqualTo(records.get(0).getContext().getExecutionId());
    }

    @Test
    void everyListenerReceivesTheSameRecordEvenWhenAnotherListenerThrows() throws Exception {
        List<TraceRecord> first = new ArrayList<>();
        List<TraceRecord> throwing = new ArrayList<>();
        List<TraceRecord> last = new ArrayList<>();
        ComponentExecutionListener thrower =
                record -> {
                    throwing.add(record);
                    throw new IllegalStateException("listener boom");
                };
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(first::add, thrower, last::add));

        assertThatCode(
                        () -> {
                            runnerContext.reportExecutionStarted(
                                    "call-1",
                                    ExecutionReporter.EntityTypes.LLM,
                                    "model-a",
                                    Map.of());
                            runnerContext.reportExecutionSucceeded(
                                    "call-1",
                                    ExecutionReporter.EntityTypes.LLM,
                                    "model-a",
                                    Map.of());
                            runnerContext.reportExecutionFailed(
                                    "call-1",
                                    ExecutionReporter.EntityTypes.LLM,
                                    "model-a",
                                    Map.of(),
                                    new IllegalStateException("call failed"),
                                    null);
                        })
                .doesNotThrowAnyException();

        assertThat(first)
                .extracting(TraceRecord::getStatus)
                .containsExactly(
                        TraceRecord.Statuses.STARTED,
                        TraceRecord.Statuses.SUCCESS,
                        TraceRecord.Statuses.FAILED);
        assertThat(throwing).hasSize(first.size());
        assertThat(last).hasSize(first.size());
        for (int i = 0; i < first.size(); i++) {
            assertThat(throwing.get(i)).isSameAs(first.get(i));
            assertThat(last.get(i)).isSameAs(first.get(i));
        }
    }

    @Test
    void reportingWithoutListenersIsANoOp() throws Exception {
        RunnerContextImpl runnerContext =
                new RunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        runnerContext.switchActionContext(
                "chat_model_action",
                null,
                new ArrayList<>(),
                null,
                "business-key",
                "obs-1",
                false,
                null);

        assertThatCode(
                        () -> {
                            runnerContext.reportExecutionStarted(
                                    "call-1",
                                    ExecutionReporter.EntityTypes.LLM,
                                    "model-a",
                                    Map.of());
                            runnerContext.reportExecutionSucceeded(
                                    "call-1",
                                    ExecutionReporter.EntityTypes.LLM,
                                    "model-a",
                                    Map.of());
                        })
                .doesNotThrowAnyException();
    }

    @Test
    void pythonReporterBridgePreservesMetadataAndPythonErrorFields() throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        PythonRunnerContextImpl runnerContext =
                new PythonRunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(records::add));

        String metadata = "{\"toolCallId\":\"call-1\",\"toolType\":\"function\"}";
        runnerContext.reportExecutionCreatedJson(
                "call-1", ExecutionReporter.EntityTypes.TOOL, "search", metadata);
        runnerContext.reportExecutionStartedAtJson(
                "call-1",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                metadata,
                "2026-01-01T00:00:01.001Z");
        runnerContext.reportExecutionFailedAtJson(
                "call-1",
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                metadata,
                "builtins.ValueError",
                "bad response",
                ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED,
                "2026-01-01T00:00:01.125Z");

        assertThat(records)
                .extracting(TraceRecord::getStatus)
                .containsExactly(
                        TraceRecord.Statuses.CREATED,
                        TraceRecord.Statuses.STARTED,
                        TraceRecord.Statuses.FAILED);
        assertThat(records)
                .allSatisfy(
                        record -> {
                            assertThat(record.getContext().getEntityType())
                                    .isEqualTo(ExecutionReporter.EntityTypes.TOOL);
                            assertThat(record.getContext().getEntityName()).isEqualTo("search");
                            assertThat(record.getContext().getEntityMetadata())
                                    .containsEntry("toolCallId", "call-1")
                                    .containsEntry("toolType", "function");
                            assertThat(record.getContext().getExecutionId())
                                    .isEqualTo(records.get(0).getContext().getExecutionId());
                        });
        TraceRecord failure = records.get(2);
        // Python reports cross the bridge as strings and must reach listeners verbatim.
        assertThat(failure.getAttributes())
                .containsEntry("errorType", "builtins.ValueError")
                .containsEntry("errorMessage", "bad response")
                .doesNotContainKeys("status", "problemCategory");
        assertThat(failure.getProblemCategory())
                .isEqualTo(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
        assertThat(records.get(1).getTimestamp()).isEqualTo("2026-01-01T00:00:01.001Z");
        assertThat(failure.getTimestamp()).isEqualTo("2026-01-01T00:00:01.125Z");
    }

    @Test
    void pythonReporterWithoutTimestampsUsesTheSameLifecycleAndErrorSchema() throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        PythonRunnerContextImpl runnerContext =
                new PythonRunnerContextImpl(null, () -> {}, emptyAgentPlan(), null, "job");
        switchToChatModelAction(runnerContext, List.of(records::add));

        runnerContext.reportExecutionStartedJson(
                "call-1", ExecutionReporter.EntityTypes.PARSER, "json", "");
        runnerContext.reportExecutionSucceededJson(
                "call-1", ExecutionReporter.EntityTypes.PARSER, "json", null);
        runnerContext.reportExecutionFailedJson(
                "call-1",
                ExecutionReporter.EntityTypes.PARSER,
                "json",
                null,
                "builtins.ValueError",
                "invalid JSON",
                ExecutionReporter.ProblemCategories.MODEL_OUTPUT_PARSE_ERROR);

        assertThat(records)
                .extracting(TraceRecord::getStatus)
                .containsExactly(
                        TraceRecord.Statuses.STARTED,
                        TraceRecord.Statuses.SUCCESS,
                        TraceRecord.Statuses.FAILED);
        assertThat(records)
                .allSatisfy(
                        record -> {
                            assertThat(record.getContext().getEntityType())
                                    .isEqualTo(ExecutionReporter.EntityTypes.PARSER);
                            assertThat(record.getContext().getEntityName()).isEqualTo("json");
                            assertThat(record.getContext().getEntityMetadata()).isEmpty();
                            assertThat(record.getTimestamp()).isNotBlank();
                        });
        TraceRecord failure = records.get(2);
        assertThat(failure.getAttributes())
                .containsEntry("errorType", "builtins.ValueError")
                .containsEntry("errorMessage", "invalid JSON");
        assertThat(failure.getProblemCategory())
                .isEqualTo(ExecutionReporter.ProblemCategories.MODEL_OUTPUT_PARSE_ERROR);
    }

    private static TraceContext switchToChatModelAction(
            RunnerContextImpl runnerContext, List<ComponentExecutionListener> listeners) {
        TraceContext actionContext =
                TraceContext.forAction(
                        TraceContext.forInputRun("business-key", "test-agent"),
                        "chat_model_action",
                        "event-1");
        runnerContext.switchActionContext(
                "chat_model_action",
                null,
                new ArrayList<>(),
                null,
                "business-key",
                "obs-1",
                false,
                new ExecutionReportingContext(actionContext, listeners));
        return actionContext;
    }

    private static AgentPlan emptyAgentPlan() {
        return new AgentPlan(new HashMap<>(), new HashMap<>());
    }
}
