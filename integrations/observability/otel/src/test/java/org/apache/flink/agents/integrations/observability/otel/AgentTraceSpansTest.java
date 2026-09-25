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
package org.apache.flink.agents.integrations.observability.otel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link AgentTraceSpans}: span topology, attributes, and id determinism. */
class AgentTraceSpansTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RUN = "7f1b5d20-86c6-4a5d-b65a-9c41757f2e11";
    private static final String ACTION = "act-1111";
    private static final String LLM = "llm-2222";
    private static final String TOOL = "tool-3333";

    // The metadata ToolCallAction records: framework call id, provider call id, and tool type.
    private static final String TOOL_METADATA =
            "\"entityMetadata\":{\"toolCallId\":\"fa-call-1\",\"externalId\":\"call_abc\","
                    + "\"toolType\":\"function\"},";

    private static TraceRecord record(String json) {
        try {
            return MAPPER.readValue(json, TraceRecord.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<TraceRecord> sampleRun() {
        return List.of(
                record(
                        "{\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"businessKey\":\"order-1001\",\"agentName\":\"ReActAgent\","
                                + "\"executionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\",\"eventType\":\"_execution_started_event\","
                                + "\"status\":\"started\"}"),
                record(
                        "{\"timestamp\":\"2026-01-15T10:30:01Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"businessKey\":\"order-1001\",\"executionId\":\""
                                + LLM
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\","
                                + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
                                + "\"eventType\":\"_execution_started_event\",\"status\":\"started\"}"),
                record(
                        "{\"timestamp\":\"2026-01-15T10:30:03Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + LLM
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
                                + "\"eventType\":\"_execution_finished_event\",\"status\":\"success\","
                                + "\"eventAttributes\":{\"promptTokens\":120,\"completionTokens\":45}}"),
                record(
                        "{\"timestamp\":\"2026-01-15T10:30:04Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + TOOL
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"tool\",\"entityName\":\"get_weather\","
                                + TOOL_METADATA
                                + "\"eventType\":\"_execution_started_event\",\"status\":\"started\"}"),
                record(
                        "{\"timestamp\":\"2026-01-15T10:30:05Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + TOOL
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"tool\",\"entityName\":\"get_weather\","
                                + TOOL_METADATA
                                + "\"eventType\":\"_execution_failed_event\",\"status\":\"failed\","
                                + "\"problemCategory\":\"tool_call_failed\","
                                + "\"eventAttributes\":{\"errorType\":\"java.io.IOException\","
                                + "\"errorMessage\":\"connection refused\"}}"),
                record(
                        "{\"timestamp\":\"2026-01-15T10:30:06Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\",\"eventType\":\"_execution_finished_event\","
                                + "\"status\":\"success\"}"));
    }

    private static SpanData spanNamed(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no span named '"
                                                + name
                                                + "' in "
                                                + spans.stream()
                                                        .map(SpanData::getName)
                                                        .collect(Collectors.toList())));
    }

    @Test
    @DisplayName("One input run becomes one trace with a synthesized invoke_agent root span")
    void testRunBecomesSingleTraceWithRootSpan() {
        List<SpanData> spans = new AgentTraceSpans("test-service").assemble(sampleRun());

        assertThat(spans).hasSize(4);
        assertThat(spans.stream().map(s -> s.getSpanContext().getTraceId()).distinct()).hasSize(1);

        SpanData root = spanNamed(spans, "invoke_agent ReActAgent");
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(root.getAttributes().get(AgentTraceSpans.GEN_AI_OPERATION_NAME))
                .isEqualTo("invoke_agent");
        assertThat(root.getAttributes().get(AgentTraceSpans.GEN_AI_AGENT_NAME))
                .isEqualTo("ReActAgent");
        assertThat(root.getAttributes().get(AgentTraceSpans.GEN_AI_CONVERSATION_ID))
                .isEqualTo("order-1001");
        // Root covers the whole run.
        assertThat(root.getStartEpochNanos())
                .isEqualTo(
                        spans.stream().mapToLong(SpanData::getStartEpochNanos).min().orElseThrow());
        assertThat(root.getEndEpochNanos())
                .isEqualTo(
                        spans.stream().mapToLong(SpanData::getEndEpochNanos).max().orElseThrow());
    }

    @Test
    @DisplayName("Execution hierarchy maps to span parenting via parentExecutionId")
    void testParenting() {
        List<SpanData> spans = new AgentTraceSpans("test-service").assemble(sampleRun());

        SpanData root = spanNamed(spans, "invoke_agent ReActAgent");
        SpanData action = spanNamed(spans, "action review");
        SpanData llm = spanNamed(spans, "chat qwen-max");
        SpanData tool = spanNamed(spans, "execute_tool get_weather");

        assertThat(action.getParentSpanContext().getSpanId())
                .isEqualTo(root.getSpanContext().getSpanId());
        assertThat(llm.getParentSpanContext().getSpanId())
                .isEqualTo(action.getSpanContext().getSpanId());
        assertThat(tool.getParentSpanContext().getSpanId())
                .isEqualTo(action.getSpanContext().getSpanId());
    }

    @Test
    @DisplayName("GenAI attributes: chat span carries model + usage, tool failure carries error")
    void testGenAiAttributes() {
        List<SpanData> spans = new AgentTraceSpans("test-service").assemble(sampleRun());

        SpanData llm = spanNamed(spans, "chat qwen-max");
        assertThat(llm.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(llm.getAttributes().get(AgentTraceSpans.GEN_AI_OPERATION_NAME))
                .isEqualTo("chat");
        assertThat(llm.getAttributes().get(AgentTraceSpans.GEN_AI_REQUEST_MODEL))
                .isEqualTo("qwen-max");
        assertThat(llm.getAttributes().get(AgentTraceSpans.GEN_AI_USAGE_INPUT_TOKENS))
                .isEqualTo(120L);
        assertThat(llm.getAttributes().get(AgentTraceSpans.GEN_AI_USAGE_OUTPUT_TOKENS))
                .isEqualTo(45L);

        SpanData tool = spanNamed(spans, "execute_tool get_weather");
        // INTERNAL: the record cannot distinguish an in-process function tool from a remote
        // MCP tool, so the converter does not claim remoteness.
        assertThat(tool.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getStatus().getDescription()).isEqualTo("connection refused");
        assertThat(tool.getAttributes().get(AgentTraceSpans.ERROR_TYPE))
                .isEqualTo("java.io.IOException");
        assertThat(tool.getAttributes().get(AgentTraceSpans.GEN_AI_TOOL_NAME))
                .isEqualTo("get_weather");
        // The provider-issued id wins over the framework-assigned one.
        assertThat(tool.getAttributes().get(AgentTraceSpans.GEN_AI_TOOL_CALL_ID))
                .isEqualTo("call_abc");
        assertThat(tool.getAttributes().get(AgentTraceSpans.GEN_AI_TOOL_TYPE))
                .isEqualTo("function");
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_TOOL_TYPE)).isEqualTo("function");
    }

    @Test
    @DisplayName("Chat span without a recorded model is named 'chat' and sets no request model")
    void testChatWithoutModel() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:01Z\","
                                                        + "\"inputRunId\":\"r\",\"executionId\":\"l\","
                                                        + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                                        + "\"eventType\":\"_execution_finished_event\","
                                                        + "\"status\":\"success\"}")));

        SpanData llm = spanNamed(spans, "chat");
        assertThat(llm.getAttributes().get(AgentTraceSpans.GEN_AI_REQUEST_MODEL)).isNull();
    }

    @Test
    @DisplayName("Tool type maps onto gen_ai.tool.type well-known values, raw value always kept")
    void testToolTypeMapping() {
        assertThat(toolSpan("{\"toolCallId\":\"c\",\"toolType\":\"mcp\"}").getAttributes())
                .satisfies(
                        a -> {
                            assertThat(a.get(AgentTraceSpans.GEN_AI_TOOL_TYPE))
                                    .isEqualTo("extension");
                            assertThat(a.get(AgentTraceSpans.FA_TOOL_TYPE)).isEqualTo("mcp");
                            // Without a provider id, the framework call id is used.
                            assertThat(a.get(AgentTraceSpans.GEN_AI_TOOL_CALL_ID)).isEqualTo("c");
                        });
        assertThat(toolSpan("{\"toolType\":\"remote_function\"}").getAttributes())
                .satisfies(
                        a ->
                                assertThat(a.get(AgentTraceSpans.GEN_AI_TOOL_TYPE))
                                        .isEqualTo("extension"));
        assertThat(toolSpan("{\"toolType\":\"model_built_in\"}").getAttributes())
                .satisfies(
                        a -> {
                            assertThat(a.get(AgentTraceSpans.GEN_AI_TOOL_TYPE)).isNull();
                            assertThat(a.get(AgentTraceSpans.FA_TOOL_TYPE))
                                    .isEqualTo("model_built_in");
                        });
    }

    @Test
    @DisplayName("error.type falls back to problemCategory when no error type is recorded")
    void testErrorTypeFallsBackToProblemCategory() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:05Z\","
                                                        + "\"inputRunId\":\"r\",\"executionId\":\"t\","
                                                        + "\"entityType\":\"tool\",\"entityName\":\"lookup\","
                                                        + "\"eventType\":\"_execution_failed_event\","
                                                        + "\"status\":\"failed\","
                                                        + "\"problemCategory\":\"tool_call_failed\"}")));

        SpanData tool = spanNamed(spans, "execute_tool lookup");
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AgentTraceSpans.ERROR_TYPE))
                .isEqualTo("tool_call_failed");
    }

    private static SpanData toolSpan(String entityMetadataJson) {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:05Z\","
                                                        + "\"inputRunId\":\"r\",\"executionId\":\"t\","
                                                        + "\"entityType\":\"tool\",\"entityName\":\"lookup\","
                                                        + "\"entityMetadata\":"
                                                        + entityMetadataJson
                                                        + ",\"eventType\":\"_execution_finished_event\","
                                                        + "\"status\":\"success\"}")));
        return spanNamed(spans, "execute_tool lookup");
    }

    @Test
    @DisplayName("Ids are derived deterministically: re-assembly yields identical trace/span ids")
    void testDeterministicIds() {
        AgentTraceSpans assembler = new AgentTraceSpans("test-service");
        List<SpanData> first = assembler.assemble(sampleRun());
        List<SpanData> second = assembler.assemble(sampleRun());

        assertThat(
                        first.stream()
                                .map(s -> s.getSpanContext().getSpanId())
                                .collect(Collectors.toList()))
                .isEqualTo(
                        second.stream()
                                .map(s -> s.getSpanContext().getSpanId())
                                .collect(Collectors.toList()));
        assertThat(first.get(0).getSpanContext().getTraceId())
                .isEqualTo(second.get(0).getSpanContext().getTraceId());
    }

    @Test
    @DisplayName("Record order does not affect the assembled spans")
    void testRecordOrderDoesNotMatter() {
        AgentTraceSpans assembler = new AgentTraceSpans("test-service");
        List<TraceRecord> reversed = new ArrayList<>(sampleRun());
        Collections.reverse(reversed);

        assertThat(spanShapes(assembler.assemble(reversed)))
                .containsExactlyInAnyOrderElementsOf(spanShapes(assembler.assemble(sampleRun())));
    }

    private static List<String> spanShapes(List<SpanData> spans) {
        return spans.stream()
                .map(
                        s ->
                                s.getName()
                                        + "|"
                                        + s.getSpanContext().getSpanId()
                                        + "|"
                                        + s.getParentSpanContext().getSpanId()
                                        + "|"
                                        + s.getStartEpochNanos()
                                        + "|"
                                        + s.getEndEpochNanos()
                                        + "|"
                                        + s.getStatus().getStatusCode()
                                        + "|"
                                        + s.getAttributes())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("Every execution span carries the framework correlation attributes")
    void testCorrelationAttributes() {
        SpanData tool =
                spanNamed(
                        new AgentTraceSpans("test-service").assemble(sampleRun()),
                        "execute_tool get_weather");

        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_INPUT_RUN_ID)).isEqualTo(RUN);
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_EXECUTION_ID)).isEqualTo(TOOL);
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_ENTITY_TYPE)).isEqualTo("tool");
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_ENTITY_NAME))
                .isEqualTo("get_weather");
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_EXECUTION_STATUS))
                .isEqualTo("failed");
    }

    @Test
    @DisplayName("A reused execution becomes a point-in-time span with status attribute 'reused'")
    void testReusedExecution() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + ACTION
                                                        + "\","
                                                        + "\"entityType\":\"action\",\"entityName\":\"review\","
                                                        + "\"eventType\":\"_execution_reused_event\","
                                                        + "\"status\":\"reused\"}")));

        SpanData reused = spanNamed(spans, "action review");
        assertThat(reused.getStartEpochNanos()).isEqualTo(reused.getEndEpochNanos());
        assertThat(reused.getAttributes().get(AgentTraceSpans.FA_EXECUTION_STATUS))
                .isEqualTo("reused");
        // A reused execution is single-record by design: complete, no diagnostic.
        assertThat(reused.getAttributes().get(AgentTraceSpans.FA_EXECUTION_INCOMPLETE)).isNull();
    }

    @Test
    @DisplayName("A started execution without a terminal record is exported as incomplete")
    void testIncompleteExecution() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + LLM
                                                        + "\","
                                                        + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                                        + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
                                                        + "\"eventType\":\"_execution_started_event\","
                                                        + "\"status\":\"started\"}")));

        SpanData span = spanNamed(spans, "chat qwen-max");
        assertThat(span.getAttributes().get(AgentTraceSpans.FA_EXECUTION_INCOMPLETE)).isTrue();
        assertThat(span.getStartEpochNanos()).isEqualTo(span.getEndEpochNanos());
        // UNSET, not ERROR: a missing terminal is indistinguishable between a crash, a dropped
        // best-effort write, and recovery discarding the pairing.
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    @DisplayName("Incomplete and start-less executions produce machine-readable diagnostics")
    void testDiagnosticsForIncompleteAndMissingStart() {
        List<ConverterDiagnostic> diagnostics = new ArrayList<>();
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + LLM
                                                        + "\","
                                                        + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                                        + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
                                                        + "\"eventType\":\"_execution_started_event\","
                                                        + "\"status\":\"started\"}"),
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:02Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + TOOL
                                                        + "\","
                                                        + "\"entityType\":\"tool\",\"entityName\":\"get_weather\","
                                                        + "\"eventType\":\"_execution_finished_event\","
                                                        + "\"status\":\"success\"}")),
                                diagnostics);

        assertThat(spans).hasSize(3); // run root + two executions
        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics)
                .anySatisfy(
                        d -> {
                            assertThat(d.getCode())
                                    .isEqualTo(ConverterDiagnostic.INCOMPLETE_EXECUTION);
                            assertThat(d.getExecutionId()).isEqualTo(LLM);
                        });
        assertThat(diagnostics)
                .anySatisfy(
                        d -> {
                            assertThat(d.getCode()).isEqualTo(ConverterDiagnostic.MISSING_START);
                            assertThat(d.getExecutionId()).isEqualTo(TOOL);
                        });
        SpanData tool = spanNamed(spans, "execute_tool get_weather");
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_EXECUTION_INCOMPLETE)).isTrue();
    }

    @Test
    @DisplayName("Parser executions map to 'parse {name}', custom operation name, INTERNAL kind")
    void testParserMapping() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\"p-1\","
                                                        + "\"entityType\":\"parser\","
                                                        + "\"entityName\":\"json_output\","
                                                        + "\"eventType\":\"_execution_started_event\","
                                                        + "\"status\":\"started\"}"),
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:01Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\"p-1\","
                                                        + "\"entityType\":\"parser\","
                                                        + "\"entityName\":\"json_output\","
                                                        + "\"eventType\":\"_execution_finished_event\","
                                                        + "\"status\":\"success\"}")));

        SpanData parser = spanNamed(spans, "parse json_output");
        assertThat(parser.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(parser.getAttributes().get(AgentTraceSpans.GEN_AI_OPERATION_NAME))
                .isEqualTo("parse");
    }

    @Test
    @DisplayName("Business events and records without trace context are ignored")
    void testNonLifecycleRecordsIgnored() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"eventType\":\"_chat_request_event\","
                                                        + "\"eventAttributes\":{\"model\":\"gpt-4\"}}"),
                                        record(
                                                "{\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"eventType\":\"_execution_started_event\","
                                                        + "\"status\":\"started\"}")));

        assertThat(spans).isEmpty();
    }
}
