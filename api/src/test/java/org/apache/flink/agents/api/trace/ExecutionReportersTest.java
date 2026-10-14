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
package org.apache.flink.agents.api.trace;

import org.apache.flink.agents.api.context.RunnerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/** Tests for {@link ExecutionReporters}. */
class ExecutionReportersTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initialReportsReturnDistinctIdsAndLaterReportsReuseThem(boolean reportingFails)
            throws Exception {
        RunnerContext ctx = mockReportingContext(reportingFails);
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        Map<String, Object> metadata = Map.of("model", "requested");
        String timestamp = "2026-01-01T00:00:00Z";

        String createdId =
                ExecutionReporters.created(ctx, ExecutionReporter.EntityTypes.TOOL, "search");
        String startedId =
                ExecutionReporters.started(
                        ctx, ExecutionReporter.EntityTypes.LLM, "model-a", metadata);
        String timedId =
                ExecutionReporters.startedAt(
                        ctx, ExecutionReporter.EntityTypes.PARSER, "json", Map.of(), timestamp);
        assertThat(List.of(createdId, startedId, timedId))
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());

        ExecutionReporters.started(
                ctx, createdId, ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        ExecutionReporters.succeeded(ctx, createdId, ExecutionReporter.EntityTypes.TOOL, "search");
        verify(reporter)
                .reportExecutionCreated(
                        createdId, ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        verify(reporter)
                .reportExecutionStarted(
                        createdId, ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        verify(reporter)
                .reportExecutionSucceeded(
                        createdId, ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        verify(reporter)
                .reportExecutionStarted(
                        startedId, ExecutionReporter.EntityTypes.LLM, "model-a", metadata);
        verify(reporter)
                .reportExecutionStartedAt(
                        timedId, ExecutionReporter.EntityTypes.PARSER, "json", Map.of(), timestamp);
    }

    @Test
    void failedIgnoresReporterFailureAndSuppressesItOnBusinessError() throws Exception {
        RunnerContext ctx = mockReportingContext(false);
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        RuntimeException businessError = new RuntimeException("business failed");
        Exception reportError = new Exception("report failed");
        doThrow(reportError)
                .when(reporter)
                .reportExecutionFailed(
                        eq("call-1"),
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        anyMap(),
                        same(businessError),
                        eq(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED));

        assertThatCode(
                        () ->
                                ExecutionReporters.failed(
                                        ctx,
                                        "call-1",
                                        ExecutionReporter.EntityTypes.TOOL,
                                        "search",
                                        Map.of("toolCallId", "call-1"),
                                        businessError,
                                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED))
                .doesNotThrowAnyException();

        assertThat(businessError.getSuppressed()).containsExactly(reportError);
    }

    @Test
    void helpersReturnNewIdentitiesWithoutExecutionReporter() {
        RunnerContext ctx = mock(RunnerContext.class);
        RuntimeException businessError = new RuntimeException("business failed");

        String createdId =
                ExecutionReporters.created(ctx, ExecutionReporter.EntityTypes.TOOL, "search", null);
        String startedId =
                ExecutionReporters.started(ctx, ExecutionReporter.EntityTypes.LLM, "model-a", null);
        String timedId =
                ExecutionReporters.startedAt(
                        ctx,
                        ExecutionReporter.EntityTypes.PARSER,
                        "json",
                        Map.of(),
                        "2026-01-01T00:00:00Z");
        assertThat(List.of(createdId, startedId, timedId))
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
        ExecutionReporters.succeeded(ctx, startedId, ExecutionReporter.EntityTypes.LLM, "model-a");
        ExecutionReporters.failed(
                ctx,
                startedId,
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                businessError,
                ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
        assertThat(businessError).hasNoSuppressedExceptions();
    }

    private static RunnerContext mockReportingContext(boolean reportingFails) {
        return mock(
                RunnerContext.class,
                withSettings()
                        .extraInterfaces(ExecutionReporter.class)
                        .defaultAnswer(
                                invocation -> {
                                    if (reportingFails
                                            && invocation
                                                    .getMethod()
                                                    .getName()
                                                    .startsWith("reportExecution")) {
                                        throw new Exception("report failed");
                                    }
                                    return RETURNS_DEFAULTS.answer(invocation);
                                }));
    }
}
