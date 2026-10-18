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
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.api.trace.TraceRecords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end test: Trace Log JSONL file in, spans out through an in-memory exporter. */
class TraceLogOTelExporterTest {

    /** Collects exported spans in memory; no network involved. */
    private static final class CollectingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();
        private final List<Integer> batchSizes = new ArrayList<>();
        private int failingCall = -1;
        private boolean shutDown;

        @Override
        public CompletableResultCode export(Collection<SpanData> collection) {
            if (batchSizes.size() == failingCall) {
                batchSizes.add(collection.size());
                return CompletableResultCode.ofFailure();
            }
            batchSizes.add(collection.size());
            spans.addAll(collection);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutDown = true;
            return CompletableResultCode.ofSuccess();
        }
    }

    @Test
    @DisplayName("Exports a JSONL Trace Log file, reading Event records without making spans")
    void testExportJsonlFile(@TempDir Path tempDir) throws Exception {
        String run = "7f1b5d20-86c6-4a5d-b65a-9c41757f2e11";
        Path log = tempDir.resolve("traces-job-task-0.log");
        Files.write(
                log,
                List.of(
                        // An Event record: read but produces no span.
                        "{\"schemaVersion\":1,\"timestamp\":\"2026-01-15T10:29:59Z\","
                                + "\"inputRunId\":\""
                                + run
                                + "\",\"entityType\":\"event\",\"entityName\":\"_input_event\","
                                + "\"entityMetadata\":{\"eventId\":\"e-1\"},\"attributes\":{}}",
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\""
                                + run
                                + "\","
                                + "\"agentName\":\"ReActAgent\",\"executionId\":\"act-1\","
                                + "\"entityType\":\"action\",\"entityName\":\"review\","
                                + "\"status\":\"started\"}",
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:02Z\",\"inputRunId\":\""
                                + run
                                + "\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"status\":\"success\"}"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder()
                        .setServiceName("test-job")
                        .setSpanExporter(collector)
                        .build();

        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

        assertThat(summary.getRecordsRead()).isEqualTo(3);
        // Root run span + the action span.
        assertThat(summary.getSpansExported()).isEqualTo(2);
        assertThat(collector.spans).hasSize(2);
        assertThat(collector.spans)
                .allSatisfy(
                        span ->
                                assertThat(
                                                span.getResource()
                                                        .getAttributes()
                                                        .get(
                                                                io.opentelemetry.api.common
                                                                        .AttributeKey.stringKey(
                                                                        "service.name")))
                                        .isEqualTo("test-job"));

        exporter.shutdown();
        assertThat(collector.shutDown).isTrue();
    }

    @Test
    @DisplayName("A malformed record yields a MALFORMED_RECORD diagnostic naming the file")
    void testMalformedRecordDiagnostic(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("traces-job-task-0.log");
        Files.write(
                log,
                List.of(
                        "{\"timestamp\": not-valid",
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\"run-1\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"status\":\"started\"}"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder()
                        .setServiceName("test-job")
                        .setSpanExporter(collector)
                        .build();

        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

        assertThat(summary.getDiagnostics())
                .anySatisfy(
                        d -> {
                            assertThat(d.getCode()).isEqualTo(ConverterDiagnostic.MALFORMED_RECORD);
                            assertThat(d.getFilePath()).isEqualTo(log.toString());
                        });
        exporter.shutdown();
    }

    @Test
    @DisplayName("A file that is not JSON from its first byte does not stop the other files")
    void testUnparseableFileDoesNotStopOtherFiles(@TempDir Path tempDir) throws Exception {
        Path broken = tempDir.resolve("traces-job-task-0.log");
        Files.write(broken, List.of("not-valid"));
        Path valid = tempDir.resolve("traces-job-task-1.log");
        String action =
                "{\"schemaVersion\":1,\"attributes\":{},\"inputRunId\":\"run-1\","
                        + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                        + "\"entityName\":\"review\",";
        Files.write(
                valid,
                List.of(
                        action + "\"timestamp\":\"2026-01-15T10:30:00Z\",\"status\":\"started\"}",
                        action + "\"timestamp\":\"2026-01-15T10:30:01Z\",\"status\":\"success\"}"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder().setSpanExporter(collector).build();
        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(broken, valid));

        assertThat(summary.getRecordsRead()).isEqualTo(2);
        assertThat(collector.spans).hasSize(2);
        assertThat(summary.getDiagnostics())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.getCode()).isEqualTo(ConverterDiagnostic.MALFORMED_RECORD);
                            assertThat(d.getFilePath()).isEqualTo(broken.toString());
                        });
        exporter.shutdown();
    }

    @Test
    @DisplayName("A directory argument is expanded to its traces-*.log and *.trace-log.log files")
    void testDirectoryDiscovery(@TempDir Path tempDir) throws Exception {
        Files.write(
                tempDir.resolve("traces-job-task-0.log"),
                List.of(
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\"run-1\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"status\":\"started\"}"));
        Files.write(
                tempDir.resolve("job-task-1.trace-log.log"),
                List.of(
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:01Z\",\"inputRunId\":\"run-2\","
                                + "\"executionId\":\"act-2\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"status\":\"started\"}"));
        // Not matching the File trace logger naming contract: ignored during discovery.
        Files.write(tempDir.resolve("other.txt"), List.of("not a log"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder()
                        .setServiceName("test-job")
                        .setSpanExporter(collector)
                        .build();

        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(tempDir));

        assertThat(summary.getRecordsRead()).isEqualTo(2);
        // Two runs, each with a root span and one (incomplete) action span.
        assertThat(summary.getSpansExported()).isEqualTo(4);
        exporter.shutdown();
    }

    /** Two runs, each with a finished action: four spans. */
    private static Path twoRunLog(Path tempDir) throws Exception {
        Path log = tempDir.resolve("traces-job-task-0.log");
        List<String> lines = new ArrayList<>();
        for (String run : List.of("run-1", "run-2")) {
            for (String event : List.of("started", "finished")) {
                lines.add(
                        "{\"schemaVersion\":1,\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:0"
                                + (event.equals("started") ? "0" : "1")
                                + "Z\",\"inputRunId\":\""
                                + run
                                + "\",\"executionId\":\"act-"
                                + run
                                + "\",\"entityType\":\"action\",\"entityName\":\"review\","
                                + "\"status\":\""
                                + (event.equals("started") ? "started" : "success")
                                + "\"}");
            }
        }
        Files.write(log, lines);
        return log;
    }

    @Test
    @DisplayName("Spans are exported in batches of at most the configured size")
    void testExportsInBoundedBatches(@TempDir Path tempDir) throws Exception {
        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder()
                        .setSpanExporter(collector)
                        .setMaxExportBatchSize(3)
                        .build();

        TraceLogOTelExporter.ExportSummary summary =
                exporter.exportFiles(List.of(twoRunLog(tempDir)));

        assertThat(summary.getSpansExported()).isEqualTo(4);
        assertThat(collector.batchSizes).containsExactly(3, 1);
        exporter.shutdown();
    }

    @Test
    @DisplayName("A failed batch reports how many spans the earlier batches delivered")
    void testFailedBatchReportsPartialProgress(@TempDir Path tempDir) throws Exception {
        CollectingSpanExporter collector = new CollectingSpanExporter();
        collector.failingCall = 1;
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder()
                        .setSpanExporter(collector)
                        .setMaxExportBatchSize(3)
                        .build();

        assertThatThrownBy(() -> exporter.exportFiles(List.of(twoRunLog(tempDir))))
                .isInstanceOfSatisfying(
                        TraceLogOTelExporter.ExportFailedException.class,
                        e -> {
                            assertThat(e.getSpansExported()).isEqualTo(3);
                            assertThat(e.getSpansTotal()).isEqualTo(4);
                        });
        assertThat(collector.spans).hasSize(3);
        exporter.shutdown();
    }

    @Test
    @DisplayName("Rejects a non-positive export batch size")
    void testRejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> TraceLogOTelExporter.builder().setMaxExportBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects unsupported OTLP protocols at build time")
    void testUnsupportedProtocol() {
        try {
            TraceLogOTelExporter.builder().setProtocol("carrier-pigeon").build();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("carrier-pigeon");
        }
    }

    @Test
    @DisplayName("Records written by the Trace Log serializer export as one parented trace")
    void testReadsSerializedTraceRecords(@TempDir Path tempDir) throws Exception {
        TraceContext action =
                TraceContext.forAction(
                        TraceContext.forInputRun("order-1001", "ReActAgent"), "review", "input");
        TraceContext llm =
                action.childExecution("llm-1", "llm", "chatModel", Map.of("model", "qwen-max"));
        TraceContext llmDone =
                action.childExecution(
                        "llm-1",
                        "llm",
                        "chatModel",
                        Map.of(
                                "model",
                                "qwen-max",
                                "usage",
                                Map.of("promptTokens", 120, "completionTokens", 45)));
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        for (TraceRecord record :
                List.of(
                        TraceRecords.started(action),
                        TraceRecords.started(llm),
                        TraceRecords.succeeded(llmDone),
                        TraceRecords.succeeded(action))) {
            lines.add(mapper.writeValueAsString(record));
        }
        Path log = tempDir.resolve("traces-job-task-0.log");
        Files.write(log, lines);

        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder().setSpanExporter(collector).build();
        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

        assertThat(summary.getDiagnostics()).isEmpty();
        assertThat(collector.spans).hasSize(3);
        SpanData actionSpan = spanNamed(collector.spans, "action review");
        SpanData chatSpan = spanNamed(collector.spans, "chat qwen-max");
        assertThat(chatSpan.getParentSpanId()).isEqualTo(actionSpan.getSpanId());
        assertThat(chatSpan.getAttributes().get(AgentTraceSpans.GEN_AI_USAGE_INPUT_TOKENS))
                .isEqualTo(120L);
        assertThat(chatSpan.getAttributes().get(AgentTraceSpans.GEN_AI_USAGE_OUTPUT_TOKENS))
                .isEqualTo(45L);
        exporter.shutdown();
    }

    @Test
    @DisplayName("A record without schemaVersion is malformed; reading continues with the next")
    void testMissingSchemaVersionIsMalformed(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("traces-job-task-0.log");
        String started =
                "\"attributes\":{},\"timestamp\":\"2026-01-15T10:30:00Z\","
                        + "\"inputRunId\":\"run-1\",\"executionId\":\"act-1\","
                        + "\"entityType\":\"action\",\"entityName\":\"review\","
                        + "\"status\":\"started\"}";
        Files.write(log, List.of("{" + started, "{\"schemaVersion\":1," + started));

        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder()
                        .setSpanExporter(new CollectingSpanExporter())
                        .build();
        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

        assertThat(summary.getRecordsRead()).isEqualTo(1);
        assertThat(summary.getDiagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.getCode())
                                        .isEqualTo(ConverterDiagnostic.MALFORMED_RECORD));
        exporter.shutdown();
    }

    @Test
    @DisplayName("Fields the File and SLF4J trace loggers add around a record are ignored")
    void testLoggerFieldsAreIgnored(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("traces-job-task-0.log");
        Files.write(
                log,
                List.of(
                        "{\"schemaVersion\":1,\"level\":\"STANDARD\",\"jobId\":\"j\","
                                + "\"taskName\":\"t\",\"subtaskId\":0,\"attributes\":{},"
                                + "\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\"run-1\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\",\"status\":\"started\"}"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        TraceLogOTelExporter exporter =
                TraceLogOTelExporter.builder().setSpanExporter(collector).build();
        TraceLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

        assertThat(summary.getRecordsRead()).isEqualTo(1);
        assertThat(collector.spans).hasSize(2);
        exporter.shutdown();
    }

    private static SpanData spanNamed(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named '" + name + "'"));
    }
}
