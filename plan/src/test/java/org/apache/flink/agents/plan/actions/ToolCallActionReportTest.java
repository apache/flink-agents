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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolExecutionMetadataProvider;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Tests for tool-call execution reports. */
class ToolCallActionReportTest {

    @Test
    void processToolRequestReportsEachToolCall() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        Tool tool =
                new ReportingTool(
                        ToolType.MCP,
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search-server"),
                        ToolResponse.success("ok"));
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("original_id", "external-call-1");
        toolCall.put("function", function);
        ToolRequestEvent request = new ToolRequestEvent("test-model", List.of(toolCall));

        ToolCallAction.processToolRequest(request, ctx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, request.getId().toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, "call-1");
        metadata.put(ToolExecutionMetadataKeys.EXTERNAL_ID, "external-call-1");
        metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, ToolType.MCP.getValue());
        metadata.put(ToolExecutionMetadataKeys.MCP_SERVER, "search-server");
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        ArgumentCaptor<String> startedAt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finishedAt = ArgumentCaptor.forClass(String.class);
        verify(reporter)
                .reportExecutionStartedAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        eq(metadata),
                        startedAt.capture());
        verify(reporter)
                .reportExecutionSucceededAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        eq(metadata),
                        finishedAt.capture());
        assertThat(Instant.parse(finishedAt.getValue()))
                .isAfterOrEqualTo(Instant.parse(startedAt.getValue()));

        assertThat(sentEvents).hasSize(1);
        assertThat(sentEvents.get(0)).isInstanceOf(ToolResponseEvent.class);
    }

    @Test
    void processToolRequestMarksErrorResponseAsFailed() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        Tool tool = mock(Tool.class);
        when(tool.call(any())).thenReturn(ToolResponse.error("tool rejected request"));
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);
        ToolRequestEvent request = new ToolRequestEvent("test-model", List.of(toolCall));

        ToolCallAction.processToolRequest(request, ctx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, request.getId().toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, "call-1");
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        verify(reporter)
                .reportExecutionFailedAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        eq(metadata),
                        any(Throwable.class),
                        eq(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED),
                        anyString());
        verify(reporter, never())
                .reportExecutionSucceededAt(anyString(), anyString(), anyMap(), anyString());

        ToolResponseEvent responseEvent = (ToolResponseEvent) sentEvents.get(0);
        assertThat(responseEvent.getSuccess()).containsEntry("call-1", false);
        assertThat(responseEvent.getError()).containsEntry("call-1", "tool rejected request");
    }

    @Test
    void parallelToolCallsReportIndependentOutcomes() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        Tool tool = mock(Tool.class);
        when(tool.call(any()))
                .thenAnswer(
                        invocation -> {
                            String query =
                                    invocation
                                            .<ToolParameters>getArgument(0)
                                            .getParameter("query", String.class);
                            if ("call-2".equals(query)) {
                                throw new IllegalStateException("call-2 failed");
                            }
                            if ("call-3".equals(query)) {
                                return ToolResponse.error("call-3 rejected");
                            }
                            return ToolResponse.success("ok");
                        });
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig(true, 3));
        when(ctx.<ToolResponse>durableExecuteAllAsync(any()))
                .thenAnswer(
                        invocation -> {
                            List<DurableCallable<ToolResponse>> callables =
                                    invocation.getArgument(0);
                            List<Outcome<ToolResponse>> outcomes = new ArrayList<>();
                            for (DurableCallable<ToolResponse> callable : callables) {
                                try {
                                    outcomes.add(Outcome.success(callable.call()));
                                } catch (Exception e) {
                                    outcomes.add(Outcome.failure(e));
                                }
                            }
                            return outcomes;
                        });
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        ToolCallAction.processToolRequest(
                new ToolRequestEvent(
                        "test-model",
                        List.of(toolCall("call-1"), toolCall("call-2"), toolCall("call-3"))),
                ctx);

        ExecutionReporter reporter = (ExecutionReporter) ctx;
        verify(reporter, times(3))
                .reportExecutionStartedAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        anyMap(),
                        anyString());
        verify(reporter)
                .reportExecutionSucceededAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        anyMap(),
                        anyString());
        verify(reporter, times(2))
                .reportExecutionFailedAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        anyMap(),
                        any(Throwable.class),
                        eq(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED),
                        anyString());

        ToolResponseEvent response = (ToolResponseEvent) sentEvents.get(0);
        assertThat(response.getSuccess())
                .containsEntry("call-1", true)
                .containsEntry("call-2", false)
                .containsEntry("call-3", false);
        assertThat(response.getError())
                .containsEntry("call-2", "call-2 failed")
                .containsEntry("call-3", "call-3 rejected");
    }

    @Test
    void responseProcessingFailureDoesNotRepeatCompletedOccurrences() throws Exception {
        Tool tool = mock(Tool.class);
        when(tool.call(any()))
                .thenAnswer(
                        invocation ->
                                "call-2"
                                                .equals(
                                                        invocation
                                                                .<ToolParameters>getArgument(0)
                                                                .getParameter(
                                                                        "query", String.class))
                                        ? null
                                        : ToolResponse.success("ok"));
        RunnerContext ctx = parallelContext(tool);

        ToolCallAction.processToolRequest(parallelRequest(), ctx);

        assertReports(
                ctx,
                List.of("call-1", "call-2", "call-3"),
                List.of("call-1", "call-3"),
                List.of("call-2"));
        assertBusinessResponsesFailed(ctx);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void durableFailureIsReportedAsToolFailure(boolean async) throws Exception {
        IllegalStateException failure = new IllegalStateException("persist failed");
        Tool tool = mock(Tool.class);
        when(tool.call(any())).thenReturn(ToolResponse.success("ok"));
        RunnerContext ctx = parallelContext(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig(async, 1));
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<DurableCallable<ToolResponse>>getArgument(0).call();
                            throw failure;
                        });
        when(ctx.<ToolResponse>durableExecuteAsync(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<DurableCallable<ToolResponse>>getArgument(0).call();
                            throw failure;
                        });

        ToolCallAction.processToolRequest(parallelRequest(), ctx);

        assertReports(
                ctx,
                List.of("call-1", "call-2", "call-3"),
                List.of(),
                List.of("call-1", "call-2", "call-3"));
        verify((ExecutionReporter) ctx, times(3))
                .reportExecutionFailedAt(
                        anyString(), anyString(), anyMap(), eq(failure), anyString(), anyString());
        assertBusinessResponsesFailed(ctx);
    }

    @Test
    void parallelDurableFailureIsReportedForItsToolCall() throws Exception {
        IllegalStateException failure = new IllegalStateException("persist failed");
        Tool tool = mock(Tool.class);
        when(tool.call(any())).thenReturn(ToolResponse.success("ok"));
        RunnerContext ctx = parallelContext(tool);
        doAnswer(
                        invocation -> {
                            List<DurableCallable<ToolResponse>> callables =
                                    invocation.getArgument(0);
                            List<Outcome<ToolResponse>> outcomes = new ArrayList<>();
                            for (DurableCallable<ToolResponse> callable : callables) {
                                outcomes.add(Outcome.success(callable.call()));
                            }
                            outcomes.set(1, Outcome.failure(failure));
                            return outcomes;
                        })
                .when(ctx)
                .durableExecuteAllAsync(any());

        ToolCallAction.processToolRequest(parallelRequest(), ctx);

        assertReports(
                ctx,
                List.of("call-1", "call-2", "call-3"),
                List.of("call-1", "call-3"),
                List.of("call-2"));
        verify((ExecutionReporter) ctx)
                .reportExecutionFailedAt(
                        anyString(), anyString(), anyMap(), eq(failure), anyString(), anyString());
        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(ctx).sendEvent(event.capture());
        assertThat(((ToolResponseEvent) event.getValue()).getSuccess())
                .containsEntry("call-1", true)
                .containsEntry("call-2", false)
                .containsEntry("call-3", true);
    }

    @Test
    void timeoutIsReportedAsFailureWithoutRepeatingOnLateCompletion() throws Exception {
        TimeoutException failure = new TimeoutException("request timed out");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<ToolResponse>> pending = new AtomicReference<>();
        AtomicReference<Instant> reportingStartedAt = new AtomicReference<>();
        Tool tool = mock(Tool.class);
        when(tool.call(any()))
                .thenAnswer(
                        invocation -> {
                            started.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return ToolResponse.success("ok");
                        });
        RunnerContext ctx = parallelContext(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig(true, 1));
        when(ctx.<ToolResponse>durableExecuteAsync(any()))
                .thenAnswer(
                        invocation -> {
                            DurableCallable<ToolResponse> callable = invocation.getArgument(0);
                            pending.set(worker.submit(callable::call));
                            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                            throw failure;
                        });
        doAnswer(
                        invocation -> {
                            reportingStartedAt.set(Instant.now());
                            return null;
                        })
                .when((ExecutionReporter) ctx)
                .reportExecutionStartedAt(anyString(), anyString(), anyMap(), anyString());

        try {
            ToolCallAction.processToolRequest(
                    new ToolRequestEvent("test-model", List.of(toolCall("call-1"))), ctx);
            assertReports(ctx, List.of("call-1"), List.of(), List.of("call-1"));
            ArgumentCaptor<String> finishedAt = ArgumentCaptor.forClass(String.class);
            verify((ExecutionReporter) ctx)
                    .reportExecutionFailedAt(
                            anyString(),
                            anyString(),
                            anyMap(),
                            eq(failure),
                            anyString(),
                            finishedAt.capture());
            assertThat(Instant.parse(finishedAt.getValue()))
                    .isBeforeOrEqualTo(reportingStartedAt.get());
            assertBusinessResponsesFailed(ctx);
        } finally {
            release.countDown();
            worker.shutdown();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(pending.get().get().isSuccess()).isTrue();
        assertReports(ctx, List.of("call-1"), List.of(), List.of("call-1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parallelTimeoutTimestampPrecedesResponseProcessingAndReporting(
            boolean completeDuringReporting) throws Exception {
        TimeoutException failure = new TimeoutException("batch timed out");
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<ToolResponse>> pending = new ArrayList<>();
        AtomicReference<Instant> responseProcessingStartedAt = new AtomicReference<>();
        ToolResponse firstResponse = mock(ToolResponse.class);
        when(firstResponse.isSuccess())
                .thenAnswer(
                        invocation -> {
                            responseProcessingStartedAt.compareAndSet(null, Instant.now());
                            return true;
                        });
        Tool tool = mock(Tool.class);
        when(tool.call(any()))
                .thenAnswer(
                        invocation -> {
                            ToolParameters parameters = invocation.getArgument(0);
                            if ("call-1".equals(parameters.getParameter("query"))) {
                                return firstResponse;
                            }
                            started.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return ToolResponse.success("late result");
                        });
        RunnerContext ctx = parallelContext(tool);
        doAnswer(
                        invocation -> {
                            List<DurableCallable<ToolResponse>> callables =
                                    invocation.getArgument(0);
                            ToolResponse response = callables.get(0).call();
                            pending.add(workers.submit(callables.get(1)::call));
                            pending.add(workers.submit(callables.get(2)::call));
                            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                            return List.of(
                                    Outcome.success(response),
                                    Outcome.failure(failure),
                                    Outcome.failure(failure));
                        })
                .when(ctx)
                .durableExecuteAllAsync(any());
        doAnswer(
                        invocation -> {
                            if (completeDuringReporting) {
                                release.countDown();
                                for (Future<ToolResponse> future : pending) {
                                    future.get(5, TimeUnit.SECONDS);
                                }
                            }
                            return null;
                        })
                .when((ExecutionReporter) ctx)
                .reportExecutionStartedAt(anyString(), anyString(), anyMap(), anyString());

        try {
            ToolCallAction.processToolRequest(parallelRequest(), ctx);
            ArgumentCaptor<String> finishedAt = ArgumentCaptor.forClass(String.class);
            verify((ExecutionReporter) ctx, times(2))
                    .reportExecutionFailedAt(
                            anyString(),
                            anyString(),
                            anyMap(),
                            eq(failure),
                            anyString(),
                            finishedAt.capture());
            assertThat(finishedAt.getAllValues().get(0))
                    .isEqualTo(finishedAt.getAllValues().get(1));
            assertThat(Instant.parse(finishedAt.getValue()))
                    .isBeforeOrEqualTo(responseProcessingStartedAt.get());
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertReports(
                ctx,
                List.of("call-1", "call-2", "call-3"),
                List.of("call-1"),
                List.of("call-2", "call-3"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parallelTimeoutOmitsStartsAfterResultObservation(boolean startsAfterObservation)
            throws Exception {
        TimeoutException failure = new TimeoutException("batch timed out");
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Instant observedAt = base.plusSeconds(1);
        Instant delayedStart = startsAfterObservation ? observedAt.plusSeconds(1) : observedAt;
        AtomicReference<Instant> now = new AtomicReference<>(base);
        List<DurableCallable<ToolResponse>> delayed = new ArrayList<>();
        Tool tool = mock(Tool.class);
        when(tool.call(any())).thenReturn(ToolResponse.success("ok"));
        RunnerContext ctx = parallelContext(tool);
        doAnswer(
                        invocation -> {
                            List<DurableCallable<ToolResponse>> callables =
                                    invocation.getArgument(0);
                            ToolResponse first = callables.get(0).call();
                            delayed.addAll(callables.subList(1, callables.size()));
                            now.set(observedAt);
                            return List.of(
                                    Outcome.success(first),
                                    Outcome.failure(failure),
                                    Outcome.failure(failure));
                        })
                .when(ctx)
                .durableExecuteAllAsync(any());
        doAnswer(
                        invocation -> {
                            Map<String, Object> metadata = invocation.getArgument(2);
                            if ("call-1"
                                    .equals(metadata.get(ToolExecutionMetadataKeys.TOOL_CALL_ID))) {
                                // The delayed calls enter after the Action has observed the
                                // timeout.
                                now.set(delayedStart);
                                for (DurableCallable<ToolResponse> callable : delayed) {
                                    callable.call();
                                }
                            }
                            return null;
                        })
                .when((ExecutionReporter) ctx)
                .reportExecutionStartedAt(anyString(), anyString(), anyMap(), anyString());

        try (MockedStatic<Instant> clock = mockStatic(Instant.class)) {
            clock.when(Instant::now).thenAnswer(invocation -> now.get());
            ToolCallAction.processToolRequest(parallelRequest(), ctx);
        }

        assertReports(
                ctx,
                startsAfterObservation ? List.of("call-1") : List.of("call-1", "call-2", "call-3"),
                List.of("call-1"),
                List.of("call-2", "call-3"));
        verify(tool, times(3)).call(any());
        ArgumentCaptor<String> finishedAt = ArgumentCaptor.forClass(String.class);
        verify((ExecutionReporter) ctx, times(2))
                .reportExecutionFailedAt(
                        anyString(),
                        anyString(),
                        anyMap(),
                        eq(failure),
                        anyString(),
                        finishedAt.capture());
        assertThat(finishedAt.getAllValues())
                .containsExactly(observedAt.toString(), observedAt.toString());
        ArgumentCaptor<Event> response = ArgumentCaptor.forClass(Event.class);
        verify(ctx).sendEvent(response.capture());
        assertThat(((ToolResponseEvent) response.getValue()).getSuccess())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("call-1", true, "call-2", false, "call-3", false));
    }

    private static RunnerContext parallelContext(Tool tool) throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig(true, 3));
        when(ctx.<ToolResponse>durableExecuteAllAsync(any()))
                .thenAnswer(
                        invocation -> {
                            List<DurableCallable<ToolResponse>> callables =
                                    invocation.getArgument(0);
                            List<Outcome<ToolResponse>> outcomes = new ArrayList<>();
                            for (DurableCallable<ToolResponse> callable : callables) {
                                try {
                                    outcomes.add(Outcome.success(callable.call()));
                                } catch (Exception e) {
                                    outcomes.add(Outcome.failure(e));
                                }
                            }
                            return outcomes;
                        });
        return ctx;
    }

    private static ToolRequestEvent parallelRequest() {
        return new ToolRequestEvent(
                "test-model", List.of(toolCall("call-1"), toolCall("call-2"), toolCall("call-3")));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertReports(
            RunnerContext ctx, List<String> started, List<String> succeeded, List<String> failed)
            throws Exception {
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        ArgumentCaptor<Map> starts = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> successes = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> failures = ArgumentCaptor.forClass(Map.class);
        verify(reporter, times(started.size()))
                .reportExecutionStartedAt(anyString(), anyString(), starts.capture(), anyString());
        verify(reporter, times(succeeded.size()))
                .reportExecutionSucceededAt(
                        anyString(), anyString(), successes.capture(), anyString());
        verify(reporter, times(failed.size()))
                .reportExecutionFailedAt(
                        anyString(),
                        anyString(),
                        failures.capture(),
                        any(Throwable.class),
                        anyString(),
                        anyString());
        assertThat(starts.getAllValues())
                .extracting(m -> m.get(ToolExecutionMetadataKeys.TOOL_CALL_ID))
                .containsExactlyInAnyOrderElementsOf(started);
        assertThat(successes.getAllValues())
                .extracting(m -> m.get(ToolExecutionMetadataKeys.TOOL_CALL_ID))
                .containsExactlyInAnyOrderElementsOf(succeeded);
        assertThat(failures.getAllValues())
                .extracting(m -> m.get(ToolExecutionMetadataKeys.TOOL_CALL_ID))
                .containsExactlyInAnyOrderElementsOf(failed);
    }

    private static void assertBusinessResponsesFailed(RunnerContext ctx) {
        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(ctx).sendEvent(event.capture());
        assertThat(((ToolResponseEvent) event.getValue()).getSuccess().values())
                .isNotEmpty()
                .containsOnly(false);
    }

    @Test
    void processLoadSkillToolRequestAddsSkillMetadata() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        Tool tool =
                new ReportingTool(
                        ToolType.FUNCTION,
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "math-calculator",
                                ToolExecutionMetadataKeys.SKILL_RESOURCE_PATH,
                                "README.md",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                true),
                        ToolResponse.success("skill content"));
        when(ctx.getResource("load_skill", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "load_skill");
        function.put("arguments", Map.of("name", "math-calculator", "path", "README.md"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);
        ToolRequestEvent request = new ToolRequestEvent("test-model", List.of(toolCall));

        ToolCallAction.processToolRequest(request, ctx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, request.getId().toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, "call-1");
        metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, ToolType.FUNCTION.getValue());
        metadata.put(ToolExecutionMetadataKeys.SKILL_NAME, "math-calculator");
        metadata.put(ToolExecutionMetadataKeys.SKILL_RESOURCE_PATH, "README.md");
        metadata.put(ToolExecutionMetadataKeys.SKILL_REGISTERED, true);
        verify((ExecutionReporter) ctx)
                .reportExecutionStartedAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("load_skill"),
                        eq(metadata),
                        anyString());
    }

    @Test
    void durableCacheHitDoesNotRecordToolCallLatency() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        Tool tool = mock(Tool.class);
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any())).thenReturn(ToolResponse.success("cached"));
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);

        ToolCallAction.processToolRequest(
                new ToolRequestEvent("test-model", List.of(toolCall)), ctx);

        verify(tool, never()).call(any());
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        verify(reporter, never())
                .reportExecutionStartedAt(anyString(), anyString(), anyMap(), anyString());
        verify(reporter)
                .reportExecutionSucceededAt(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        anyMap(),
                        anyString());
    }

    @Test
    void executionMetadataCannotMutateToolCallParameters() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(new MutatingMetadataTool());
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);

        ToolCallAction.processToolRequest(
                new ToolRequestEvent("test-model", List.of(toolCall)), ctx);

        ToolResponseEvent responseEvent = (ToolResponseEvent) sentEvents.get(0);
        assertThat(responseEvent.getResponses().get("call-1").getResult()).isEqualTo("flink");
    }

    private static org.apache.flink.agents.api.configuration.ReadableConfiguration
            toolCallConfig() {
        return toolCallConfig(false, 1);
    }

    private static org.apache.flink.agents.api.configuration.ReadableConfiguration toolCallConfig(
            boolean async, int parallelism) {
        return new org.apache.flink.agents.api.configuration.ReadableConfiguration() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(org.apache.flink.agents.api.configuration.ConfigOption<T> option) {
                if (option == AgentExecutionOptions.TOOL_CALL_ASYNC) {
                    return (T) Boolean.valueOf(async);
                }
                if (option == AgentExecutionOptions.TOOL_CALL_PARALLELISM) {
                    return (T) Integer.valueOf(parallelism);
                }
                return option.getDefaultValue();
            }

            @Override
            public Integer getInt(String key, Integer defaultValue) {
                return defaultValue;
            }

            @Override
            public Long getLong(String key, Long defaultValue) {
                return defaultValue;
            }

            @Override
            public Float getFloat(String key, Float defaultValue) {
                return defaultValue;
            }

            @Override
            public Double getDouble(String key, Double defaultValue) {
                return defaultValue;
            }

            @Override
            public Boolean getBool(String key, Boolean defaultValue) {
                return defaultValue;
            }

            @Override
            public String getStr(String key, String defaultValue) {
                return defaultValue;
            }
        };
    }

    private static Map<String, Object> toolCall(String id) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", id));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("function", function);
        return toolCall;
    }

    private static final class ReportingTool extends Tool implements ToolExecutionMetadataProvider {
        private final ToolType toolType;
        private final Map<String, Object> entityMetadata;
        private final ToolResponse response;

        private ReportingTool(
                ToolType toolType, Map<String, Object> entityMetadata, ToolResponse response) {
            super(new ToolMetadata("search", "Search", "{\"type\":\"object\"}"));
            this.toolType = toolType;
            this.entityMetadata = entityMetadata;
            this.response = response;
        }

        @Override
        public ToolType getToolType() {
            return toolType;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return response;
        }

        @Override
        public Map<String, Object> getToolExecutionMetadata(ToolParameters parameters) {
            return entityMetadata;
        }
    }

    private static final class MutatingMetadataTool extends Tool
            implements ToolExecutionMetadataProvider {

        private MutatingMetadataTool() {
            super(new ToolMetadata("search", "Search", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(parameters.getParameter("query"));
        }

        @Override
        public Map<String, Object> getToolExecutionMetadata(ToolParameters parameters) {
            parameters.addParameter("query", "mutated");
            return Map.of();
        }
    }
}
