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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/** Tests for {@link ExecutionReporters}. */
class ExecutionReportersTest {

    @Test
    void startedAndSucceededIgnoreReporterFailures() throws Exception {
        RunnerContext ctx = mockReportingContext();
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        Exception startedError = new Exception("started failed");
        Exception succeededError = new Exception("succeeded failed");
        doThrow(startedError)
                .when(reporter)
                .reportExecutionStarted(
                        eq(ExecutionReporter.EntityTypes.LLM), eq("model-a"), anyMap());
        doThrow(succeededError)
                .when(reporter)
                .reportExecutionSucceeded(
                        eq(ExecutionReporter.EntityTypes.LLM), eq("model-a"), anyMap());

        assertThatCode(
                        () ->
                                ExecutionReporters.started(
                                        ctx, ExecutionReporter.EntityTypes.LLM, "model-a"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                ExecutionReporters.succeeded(
                                        ctx, ExecutionReporter.EntityTypes.LLM, "model-a"))
                .doesNotThrowAnyException();

        verify(reporter)
                .reportExecutionStarted(
                        eq(ExecutionReporter.EntityTypes.LLM), eq("model-a"), anyMap());
        verify(reporter)
                .reportExecutionSucceeded(
                        eq(ExecutionReporter.EntityTypes.LLM), eq("model-a"), anyMap());
    }

    @Test
    void failedIgnoresReporterFailureAndSuppressesItOnBusinessError() throws Exception {
        RunnerContext ctx = mockReportingContext();
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        RuntimeException businessError = new RuntimeException("business failed");
        Exception reportError = new Exception("report failed");
        doThrow(reportError)
                .when(reporter)
                .reportExecutionFailed(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        anyMap(),
                        same(businessError),
                        eq(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED));

        assertThatCode(
                        () ->
                                ExecutionReporters.failed(
                                        ctx,
                                        ExecutionReporter.EntityTypes.TOOL,
                                        "search",
                                        Map.of("toolCallId", "call-1"),
                                        businessError,
                                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED))
                .doesNotThrowAnyException();

        assertThat(businessError.getSuppressed()).containsExactly(reportError);
    }

    @Test
    void helpersIgnoreContextsWithoutExecutionReporter() {
        RunnerContext ctx = mock(RunnerContext.class);
        RuntimeException businessError = new RuntimeException("business failed");

        assertThatCode(
                        () ->
                                ExecutionReporters.started(
                                        ctx, ExecutionReporter.EntityTypes.LLM, "model-a"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                ExecutionReporters.succeeded(
                                        ctx, ExecutionReporter.EntityTypes.LLM, "model-a"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                ExecutionReporters.failed(
                                        ctx,
                                        ExecutionReporter.EntityTypes.LLM,
                                        "model-a",
                                        businessError,
                                        ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED))
                .doesNotThrowAnyException();

        assertThat(businessError).hasNoSuppressedExceptions();
    }

    private static RunnerContext mockReportingContext() {
        return mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
    }
}
