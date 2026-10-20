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
import org.apache.flink.agents.api.trace.TraceRecord;
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

    // The metadata ToolCallAction records: the provider's call id and the tool type.
    private static final String TOOL_METADATA =
            "\"entityMetadata\":{\"toolCallId\":\"call_abc\",\"toolType\":\"function\"},";

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
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"businessKey\":\"order-1001\",\"agentName\":\"ReActAgent\","
                                + "\"executionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"status\":\"started\"}"),
                record(
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:01Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"businessKey\":\"order-1001\",\"executionId\":\""
                                + LLM
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\","
                                + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
                                + "\"status\":\"started\"}"),
                record(
                        "{\"schemaVersion\":1,\"timestamp\":\"2026-01-15T10:30:03Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + LLM
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                + "\"entityMetadata\":{\"model\":\"qwen-max\","
                                + "\"usage\":{\"promptTokens\":120,\"completionTokens\":45}},"
                                + "\"status\":\"success\",\"attributes\":{}}"),
                record(
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:04Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + TOOL
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"tool\",\"entityName\":\"get_weather\","
                                + TOOL_METADATA
                                + "\"status\":\"started\"}"),
                record(
                        "{\"schemaVersion\":1,\"timestamp\":\"2026-01-15T10:30:05Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + TOOL
                                + "\",\"parentExecutionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"tool\",\"entityName\":\"get_weather\","
                                + TOOL_METADATA
                                + "\"status\":\"failed\","
                                + "\"problemCategory\":\"tool_call_failed\","
                                + "\"attributes\":{\"errorType\":\"java.io.IOException\","
                                + "\"errorMessage\":\"connection refused\"}}"),
                record(
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:06Z\",\"inputRunId\":\""
                                + RUN
                                + "\",\"executionId\":\""
                                + ACTION
                                + "\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
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
        assertThat(root.getAttributes().get(AgentTraceSpans.FA_BUSINESS_KEY))
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
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:01Z\","
                                                        + "\"inputRunId\":\"r\",\"executionId\":\"l\","
                                                        + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
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
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:05Z\","
                                                        + "\"inputRunId\":\"r\",\"executionId\":\"t\","
                                                        + "\"entityType\":\"tool\",\"entityName\":\"lookup\","
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
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:05Z\","
                                                        + "\"inputRunId\":\"r\",\"executionId\":\"t\","
                                                        + "\"entityType\":\"tool\",\"entityName\":\"lookup\","
                                                        + "\"entityMetadata\":"
                                                        + entityMetadataJson
                                                        + ","
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
    @DisplayName("businessKey is always flink_agents.business_key; conversation id is opt-in")
    void testBusinessKeyAsConversationIdIsOptIn() {
        SpanData byDefault =
                spanNamed(
                        new AgentTraceSpans("test-service").assemble(sampleRun()),
                        "invoke_agent ReActAgent");
        assertThat(byDefault.getAttributes().get(AgentTraceSpans.FA_BUSINESS_KEY))
                .isEqualTo("order-1001");
        assertThat(byDefault.getAttributes().get(AgentTraceSpans.GEN_AI_CONVERSATION_ID)).isNull();

        List<SpanData> optedIn = new AgentTraceSpans("test-service", true).assemble(sampleRun());
        for (String name : List.of("invoke_agent ReActAgent", "chat qwen-max")) {
            SpanData span = spanNamed(optedIn, name);
            assertThat(span.getAttributes().get(AgentTraceSpans.FA_BUSINESS_KEY))
                    .isEqualTo("order-1001");
            assertThat(span.getAttributes().get(AgentTraceSpans.GEN_AI_CONVERSATION_ID))
                    .isEqualTo("order-1001");
        }
    }

    @Test
    @DisplayName("Tool spans carry gen_ai.agent.name when their records have one")
    void testToolSpanCarriesAgentName() {
        SpanData named =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        toolRecord(
                                                "2026-01-15T10:30:04Z",
                                                "started",
                                                ",\"agentName\":\"ReActAgent\""),
                                        toolRecord("2026-01-15T10:30:05Z", "success")))
                        .get(1);
        assertThat(named.getAttributes().get(AgentTraceSpans.GEN_AI_AGENT_NAME))
                .isEqualTo("ReActAgent");

        SpanData unnamed =
                new AgentTraceSpans("test-service")
                        .assemble(List.of(toolRecord("2026-01-15T10:30:04Z", "started")))
                        .get(1);
        assertThat(unnamed.getAttributes().get(AgentTraceSpans.GEN_AI_AGENT_NAME)).isNull();
    }

    @Test
    @DisplayName("A record with an invalid timestamp is skipped with a diagnostic")
    void testInvalidTimestampIsSkippedWithDiagnostic() {
        List<ConverterDiagnostic> diagnostics = new ArrayList<>();
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        toolRecord("yesterday", "started"),
                                        toolRecord("2026-01-15T10:30:05Z", "success")),
                                diagnostics);

        // The valid terminal record still becomes a span; the invalid start is reported.
        assertThat(spans).hasSize(2);
        assertThat(diagnostics)
                .anySatisfy(
                        d -> {
                            assertThat(d.getCode()).isEqualTo(ConverterDiagnostic.MALFORMED_RECORD);
                            assertThat(d.getMessage()).contains("yesterday");
                        });
    }

    private static TraceRecord toolRecord(String timestamp, String status) {
        return toolRecord(timestamp, status, "");
    }

    /**
     * {@code extraJson} adds fields after the status; empty attributes are added if it has none.
     */
    private static TraceRecord toolRecord(String timestamp, String status, String extraJson) {
        return record(
                "{\"schemaVersion\":1,\"timestamp\":\""
                        + timestamp
                        + "\",\"inputRunId\":\"r\",\"executionId\":\"t\","
                        + "\"entityType\":\"tool\",\"entityName\":\"lookup\","
                        + "\"status\":\""
                        + status
                        + "\""
                        + extraJson
                        + (extraJson.contains("\"attributes\"") ? "" : ",\"attributes\":{}")
                        + "}");
    }

    @Test
    @DisplayName("created -> started -> failed spans started..failed in any record order")
    void testCreatedStartedTerminal() {
        List<TraceRecord> records =
                new ArrayList<>(
                        List.of(
                                toolRecord("2026-01-15T10:30:00Z", "created"),
                                toolRecord("2026-01-15T10:30:01Z", "started"),
                                toolRecord(
                                        "2026-01-15T10:30:03Z",
                                        "failed",
                                        ",\"problemCategory\":\"tool_call_failed\"")));
        for (int pass = 0; pass < 2; pass++) {
            List<ConverterDiagnostic> diagnostics = new ArrayList<>();
            SpanData tool =
                    spanNamed(
                            new AgentTraceSpans("test-service").assemble(records, diagnostics),
                            "execute_tool lookup");

            assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(tool.getAttributes().get(AgentTraceSpans.FA_EXECUTION_STATUS))
                    .isEqualTo("failed");
            assertThat(tool.getEndEpochNanos() - tool.getStartEpochNanos())
                    .isEqualTo(2_000_000_000L);
            assertThat(tool.getAttributes().get(AgentTraceSpans.FA_EXECUTION_INCOMPLETE)).isNull();
            assertThat(diagnostics).isEmpty();
            Collections.reverse(records);
        }
    }

    @Test
    @DisplayName("A created-only execution is incomplete, not a terminal without a start")
    void testCreatedOnly() {
        List<ConverterDiagnostic> diagnostics = new ArrayList<>();
        SpanData tool =
                spanNamed(
                        new AgentTraceSpans("test-service")
                                .assemble(
                                        List.of(toolRecord("2026-01-15T10:30:00Z", "created")),
                                        diagnostics),
                        "execute_tool lookup");

        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_EXECUTION_INCOMPLETE)).isTrue();
        assertThat(tool.getStartEpochNanos()).isEqualTo(tool.getEndEpochNanos());
        assertThat(diagnostics)
                .extracting(ConverterDiagnostic::getCode)
                .containsExactly(ConverterDiagnostic.INCOMPLETE_EXECUTION);
    }

    @Test
    @DisplayName("created -> failed without a start (preparation failure) spans created..failed")
    void testCreatedThenFailedWithoutStart() {
        List<ConverterDiagnostic> diagnostics = new ArrayList<>();
        SpanData tool =
                spanNamed(
                        new AgentTraceSpans("test-service")
                                .assemble(
                                        List.of(
                                                toolRecord("2026-01-15T10:30:00Z", "created"),
                                                toolRecord("2026-01-15T10:30:00.500Z", "failed")),
                                        diagnostics),
                        "execute_tool lookup");

        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getEndEpochNanos() - tool.getStartEpochNanos()).isEqualTo(500_000_000L);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    @DisplayName("A STANDARD-level truncated error message still becomes the status description")
    void testTruncatedErrorMessage() {
        SpanData tool =
                spanNamed(
                        new AgentTraceSpans("test-service")
                                .assemble(
                                        List.of(
                                                toolRecord(
                                                        "2026-01-15T10:30:00Z",
                                                        "failed",
                                                        ",\"attributes\":{\"errorType\":\"x.Boom\","
                                                                + "\"errorMessage\":{\"truncatedString\":"
                                                                + "\"long message...\",\"omittedChars\":42}}"))),
                        "execute_tool lookup");

        assertThat(tool.getStatus().getDescription()).isEqualTo("long message...");
        assertThat(tool.getAttributes().get(AgentTraceSpans.ERROR_TYPE)).isEqualTo("x.Boom");
    }

    @Test
    @DisplayName("A reused execution becomes a point-in-time span with status attribute 'reused'")
    void testReusedExecution() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + ACTION
                                                        + "\","
                                                        + "\"entityType\":\"action\",\"entityName\":\"review\","
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
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + LLM
                                                        + "\","
                                                        + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                                        + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
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
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + LLM
                                                        + "\","
                                                        + "\"entityType\":\"llm\",\"entityName\":\"chatModel\","
                                                        + "\"entityMetadata\":{\"model\":\"qwen-max\"},"
                                                        + "\"status\":\"started\"}"),
                                        record(
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:02Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\""
                                                        + TOOL
                                                        + "\","
                                                        + "\"entityType\":\"tool\",\"entityName\":\"get_weather\","
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
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\"p-1\","
                                                        + "\"entityType\":\"parser\","
                                                        + "\"entityName\":\"json_output\","
                                                        + "\"status\":\"started\"}"),
                                        record(
                                                "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:01Z\","
                                                        + "\"inputRunId\":\""
                                                        + RUN
                                                        + "\","
                                                        + "\"executionId\":\"p-1\","
                                                        + "\"entityType\":\"parser\","
                                                        + "\"entityName\":\"json_output\","
                                                        + "\"status\":\"success\"}")));

        SpanData parser = spanNamed(spans, "parse json_output");
        assertThat(parser.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(parser.getAttributes().get(AgentTraceSpans.GEN_AI_OPERATION_NAME))
                .isEqualTo("parse");
    }

    @Test
    @DisplayName("Event records and execution records without a run are ignored")
    void testNonLifecycleRecordsIgnored() {
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        record(
                                                "{\"schemaVersion\":1,"
                                                        + "\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"inputRunId\":\"r\",\"entityType\":\"event\","
                                                        + "\"entityName\":\"_chat_request_event\","
                                                        + "\"entityMetadata\":{\"eventId\":\"e-1\"},"
                                                        + "\"attributes\":{\"model\":\"gpt-4\"}}"),
                                        record(
                                                "{\"schemaVersion\":1,"
                                                        + "\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                        + "\"executionId\":\"x\",\"entityType\":\"tool\","
                                                        + "\"entityName\":\"lookup\","
                                                        + "\"status\":\"started\",\"attributes\":{}}")));

        assertThat(spans).isEmpty();
    }

    @Test
    @DisplayName(
            "A child whose parent has no records is attached to the run root, with a diagnostic")
    void testMissingParentAttachesToRunRoot() {
        List<ConverterDiagnostic> diagnostics = new ArrayList<>();
        List<SpanData> spans =
                new AgentTraceSpans("test-service")
                        .assemble(
                                List.of(
                                        toolRecord(
                                                "2026-01-15T10:30:04Z",
                                                "started",
                                                ",\"parentExecutionId\":\"act-missing\""),
                                        toolRecord(
                                                "2026-01-15T10:30:05Z",
                                                "success",
                                                ",\"parentExecutionId\":\"act-missing\"")),
                                diagnostics);

        SpanData root = spanNamed(spans, "invoke_agent");
        SpanData tool = spanNamed(spans, "execute_tool lookup");
        assertThat(tool.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(tool.getAttributes().get(AgentTraceSpans.FA_PARENT_EXECUTION_ID))
                .isEqualTo("act-missing");
        assertThat(diagnostics)
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.getCode()).isEqualTo(ConverterDiagnostic.MISSING_PARENT);
                            assertThat(d.getExecutionId()).isEqualTo("t");
                        });
    }

    @Test
    @DisplayName("Repeated records keep the earliest of each kind, whatever their order")
    void testDuplicateRecordsKeepEarliest() {
        List<TraceRecord> records =
                new ArrayList<>(
                        List.of(
                                toolRecord("2026-01-15T10:30:01Z", "started"),
                                toolRecord("2026-01-15T10:30:02Z", "started"),
                                toolRecord("2026-01-15T10:30:04Z", "success"),
                                toolRecord("2026-01-15T10:30:06Z", "success")));
        for (int pass = 0; pass < 2; pass++) {
            SpanData tool =
                    spanNamed(
                            new AgentTraceSpans("test-service").assemble(records),
                            "execute_tool lookup");

            assertThat(tool.getEndEpochNanos() - tool.getStartEpochNanos())
                    .isEqualTo(3_000_000_000L);
            Collections.reverse(records);
        }
    }

    @Test
    @DisplayName("A sub-agent delegation maps to invoke_agent {name} with the tool call id")
    void testSubagentMapping() {
        String delegation =
                "\"inputRunId\":\"r\",\"executionId\":\"s\",\"entityType\":\"subagent\","
                        + "\"entityName\":\"researcher\","
                        + "\"entityMetadata\":{\"toolCallId\":\"call_1\"},\"attributes\":{},";
        SpanData span =
                spanNamed(
                        new AgentTraceSpans("test-service")
                                .assemble(
                                        List.of(
                                                record(
                                                        "{\"schemaVersion\":1,"
                                                                + delegation
                                                                + "\"timestamp\":\"2026-01-15T10:30:00Z\","
                                                                + "\"status\":\"started\"}"),
                                                record(
                                                        "{\"schemaVersion\":1,"
                                                                + delegation
                                                                + "\"timestamp\":\"2026-01-15T10:30:02Z\","
                                                                + "\"status\":\"success\"}"))),
                        "invoke_agent researcher");

        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(span.getAttributes().get(AgentTraceSpans.GEN_AI_OPERATION_NAME))
                .isEqualTo("invoke_agent");
        assertThat(span.getAttributes().get(AgentTraceSpans.GEN_AI_AGENT_NAME))
                .isEqualTo("researcher");
        assertThat(span.getAttributes().get(AgentTraceSpans.GEN_AI_TOOL_CALL_ID))
                .isEqualTo("call_1");
    }
}
