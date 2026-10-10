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
package org.apache.flink.agents.runtime.tracelog;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.logger.TraceLogDetail;
import org.apache.flink.agents.api.logger.TraceLogger;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.api.trace.TraceRecords;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contract tests for {@link TraceLogWriter}. */
class TraceLogWriterTest {

    @Test
    void appendAndFlushAttemptsBothOperationsAndCountsOneFailure() throws Exception {
        TraceLogger mockLogger = mock(TraceLogger.class);
        TraceLogWriter writer = TraceLogWriter.forTraceLogger(mockLogger);
        SimpleCounter writeFailures = openWithWriteFailureCounter(writer);
        TraceRecord record = inputRecord(null);
        RuntimeException appendError = new RuntimeException("append failed");
        RuntimeException flushError = new RuntimeException("flush failed");
        doThrow(appendError).when(mockLogger).append(record, TraceLogDetail.STANDARD);
        doThrow(flushError).when(mockLogger).flush();

        assertThatCode(() -> writer.appendAndFlush(record)).doesNotThrowAnyException();

        verify(mockLogger).append(record, TraceLogDetail.STANDARD);
        verify(mockLogger).flush();
        assertThat(writeFailures.getCount()).isEqualTo(1);
        assertThat(appendError.getSuppressed()).containsExactly(flushError);
    }

    @Test
    void appendAndFlushIgnoresFlushFailure() throws Exception {
        TraceLogger mockLogger = mock(TraceLogger.class);
        TraceLogWriter writer = TraceLogWriter.forTraceLogger(mockLogger);
        SimpleCounter writeFailures = openWithWriteFailureCounter(writer);
        TraceRecord record = inputRecord(null);
        doThrow(new RuntimeException("flush failed")).when(mockLogger).flush();

        assertThatCode(() -> writer.appendAndFlush(record)).doesNotThrowAnyException();

        verify(mockLogger).append(record, TraceLogDetail.STANDARD);
        verify(mockLogger).flush();
        assertThat(writeFailures.getCount()).isEqualTo(1);
    }

    @Test
    void appendAndFlushCountsEveryFailedWriteAttempt() throws Exception {
        TraceLogger mockLogger = mock(TraceLogger.class);
        TraceLogWriter writer = TraceLogWriter.forTraceLogger(mockLogger);
        SimpleCounter writeFailures = openWithWriteFailureCounter(writer);
        TraceRecord record = inputRecord(null);
        doThrow(new RuntimeException("append failed"))
                .when(mockLogger)
                .append(record, TraceLogDetail.STANDARD);

        writer.appendAndFlush(record);
        writer.appendAndFlush(record);

        verify(mockLogger, times(2)).flush();
        assertThat(writeFailures.getCount()).isEqualTo(2);
    }

    @Test
    void missingLoggerDoesNotFail() {
        TraceLogWriter writer = TraceLogWriter.forTraceLogger(null);

        assertThatCode(() -> writer.appendAndFlush(inputRecord(null))).doesNotThrowAnyException();
    }

    @Test
    void filteredRecordsDoNotCountAsWriteFailures() throws Exception {
        TraceLogger mockLogger = mock(TraceLogger.class);
        TraceLogWriter writer =
                TraceLogWriter.forTraceLogger(
                        mockLogger,
                        Map.of(
                                "trace-log.targets",
                                List.of(Map.of("scope", "ALL", "detail", "OFF"))));
        SimpleCounter writeFailures = openWithWriteFailureCounter(writer);

        writer.appendAndFlush(TraceRecords.started(actionContext("process")));
        writer.appendAndFlush(inputRecord(null));

        verify(mockLogger, times(0)).append(any(), any());
        verify(mockLogger, times(0)).flush();
        assertThat(writeFailures.getCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("selectionAndDetails")
    void writerSelectsRecordsAndPassesResolvedDetailToLogger(
            Map<String, Object> config, Map<String, TraceLogDetail> expectedDetails)
            throws Exception {
        TraceLogger mockLogger = mock(TraceLogger.class);
        TraceLogWriter writer = TraceLogWriter.forTraceLogger(mockLogger, config);
        SimpleCounter writeFailures = openWithWriteFailureCounter(writer);
        TraceContext action = actionContext("process");
        List<TraceRecord> records =
                List.of(
                        inputRecord(null),
                        TraceRecords.started(action),
                        TraceRecords.started(action.childExecution("tool", "lookup", null)),
                        TraceRecords.started(action.childExecution("tool", "other", null)));

        records.forEach(writer::appendAndFlush);

        ArgumentCaptor<TraceRecord> appended = ArgumentCaptor.forClass(TraceRecord.class);
        ArgumentCaptor<TraceLogDetail> details = ArgumentCaptor.forClass(TraceLogDetail.class);
        verify(mockLogger, times(expectedDetails.size()))
                .append(appended.capture(), details.capture());
        verify(mockLogger, times(expectedDetails.size())).flush();
        Map<String, TraceLogDetail> actualDetails = new LinkedHashMap<>();
        for (int i = 0; i < appended.getAllValues().size(); i++) {
            actualDetails.put(
                    appended.getAllValues().get(i).getContext().getEntityName(),
                    details.getAllValues().get(i));
        }
        assertThat(actualDetails).isEqualTo(expectedDetails);
        assertThat(writeFailures.getCount()).isZero();
    }

    private static Stream<Arguments> selectionAndDetails() {
        Map<String, Object> lookupTarget =
                Map.of(
                        "scope",
                        Map.of("entityType", "tool", "entityName", "lookup"),
                        "detail",
                        "VERBOSE");
        return Stream.of(
                Arguments.of(Map.of(), Map.of(InputEvent.EVENT_TYPE, TraceLogDetail.STANDARD)),
                Arguments.of(
                        Map.of(
                                "trace-log.targets",
                                List.of(Map.of("scope", "ALL", "detail", "OFF"), lookupTarget)),
                        Map.of("lookup", TraceLogDetail.VERBOSE)),
                Arguments.of(
                        Map.of("trace-log.targets", List.of(Map.of("scope", "ALL"), lookupTarget)),
                        Map.of(
                                InputEvent.EVENT_TYPE,
                                TraceLogDetail.STANDARD,
                                "process",
                                TraceLogDetail.STANDARD,
                                "lookup",
                                TraceLogDetail.VERBOSE,
                                "other",
                                TraceLogDetail.STANDARD)),
                Arguments.of(
                        Map.of(
                                "trace-log.targets",
                                List.of(
                                        Map.of("scope", "ALL", "detail", "VERBOSE"),
                                        Map.of("scope", Map.of("entityType", "tool")))),
                        Map.of(
                                InputEvent.EVENT_TYPE,
                                TraceLogDetail.VERBOSE,
                                "process",
                                TraceLogDetail.VERBOSE,
                                "lookup",
                                TraceLogDetail.VERBOSE,
                                "other",
                                TraceLogDetail.VERBOSE)),
                Arguments.of(
                        Map.of(
                                "trace-log.targets",
                                List.of(
                                        Map.of("scope", "ALL", "detail", "VERBOSE"),
                                        Map.of(
                                                "scope",
                                                Map.of("entityType", "tool"),
                                                "detail",
                                                "OFF"),
                                        Map.of(
                                                "scope",
                                                Map.of(
                                                        "entityType",
                                                        "tool",
                                                        "entityName",
                                                        "lookup"),
                                                "detail",
                                                "STANDARD"))),
                        Map.of(
                                InputEvent.EVENT_TYPE,
                                TraceLogDetail.VERBOSE,
                                "process",
                                TraceLogDetail.VERBOSE,
                                "lookup",
                                TraceLogDetail.STANDARD)),
                Arguments.of(
                        Map.of(
                                "trace-log.targets",
                                List.of(
                                        Map.of("scope", "ALL", "detail", "OFF"),
                                        Map.of(
                                                "scope",
                                                Map.of("entityType", "tool"),
                                                "detail",
                                                "VERBOSE"),
                                        Map.of(
                                                "scope",
                                                Map.of(
                                                        "entityType",
                                                        "tool",
                                                        "entityName",
                                                        "lookup")))),
                        Map.of("other", TraceLogDetail.VERBOSE)));
    }

    @Test
    void operatorWriterRejectsMixedLegacyConfigAtCreation() {
        AgentConfiguration config =
                new AgentConfiguration(
                        Map.of(
                                "trace-log.targets",
                                List.of(Map.of("scope", "ALL", "detail", "STANDARD")),
                                "event-log.level",
                                "OFF"));
        AgentPlan agentPlan = new AgentPlan(Collections.emptyMap(), Collections.emptyMap(), config);

        assertThatThrownBy(() -> TraceLogWriter.create(agentPlan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event-log.level")
                .hasMessageContaining("trace-log");
    }

    private static TraceContext actionContext(String name) {
        return TraceContext.forAction(TraceContext.forInputRun("key", "agent"), name, "input-id");
    }

    private static TraceRecord inputRecord(TraceContext sourceContext) {
        InputEvent event = new InputEvent(1L);
        return TraceRecord.create(
                TraceContext.forEvent(
                        sourceContext, event.getType(), event.getId().toString(), null, null),
                null,
                null,
                event.getAttributes());
    }

    private static SimpleCounter openWithWriteFailureCounter(TraceLogWriter writer)
            throws Exception {
        SimpleCounter writeFailures = new SimpleCounter();
        BuiltInMetrics builtInMetrics = mock(BuiltInMetrics.class);
        when(builtInMetrics.getTraceLogWriteFailuresCounter()).thenReturn(writeFailures);
        writer.open(mock(StreamingRuntimeContext.class), builtInMetrics);
        return writeFailures;
    }
}
