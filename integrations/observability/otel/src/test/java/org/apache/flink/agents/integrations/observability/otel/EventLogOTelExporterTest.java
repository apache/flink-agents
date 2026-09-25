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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end test: Event Log JSONL file in, spans out through an in-memory exporter. */
class EventLogOTelExporterTest {

    /** Collects exported spans in memory; no network involved. */
    private static final class CollectingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();
        private boolean shutDown;

        @Override
        public CompletableResultCode export(Collection<SpanData> collection) {
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
    @DisplayName("Exports a JSONL Event Log file, skipping malformed and non-trace records")
    void testExportJsonlFile(@TempDir Path tempDir) throws Exception {
        String run = "7f1b5d20-86c6-4a5d-b65a-9c41757f2e11";
        Path log = tempDir.resolve("events-job-task-0.log");
        Files.write(
                log,
                List.of(
                        // Business event without trace context: read but produces no span.
                        "{\"timestamp\":\"2026-01-15T10:29:59Z\",\"eventType\":\"_input_event\","
                                + "\"eventAttributes\":{}}",
                        "{\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\""
                                + run
                                + "\","
                                + "\"agentName\":\"ReActAgent\",\"executionId\":\"act-1\","
                                + "\"entityType\":\"action\",\"entityName\":\"review\","
                                + "\"eventType\":\"_execution_started_event\",\"status\":\"started\"}",
                        "{\"timestamp\":\"2026-01-15T10:30:02Z\",\"inputRunId\":\""
                                + run
                                + "\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"eventType\":\"_execution_finished_event\",\"status\":\"success\"}"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        EventLogOTelExporter exporter =
                EventLogOTelExporter.builder()
                        .setServiceName("test-job")
                        .setSpanExporter(collector)
                        .build();

        EventLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

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
        Path log = tempDir.resolve("events-job-task-0.log");
        Files.write(
                log,
                List.of(
                        "{\"timestamp\": not-valid",
                        "{\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\"run-1\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"eventType\":\"_execution_started_event\","
                                + "\"status\":\"started\"}"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        EventLogOTelExporter exporter =
                EventLogOTelExporter.builder()
                        .setServiceName("test-job")
                        .setSpanExporter(collector)
                        .build();

        EventLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(log));

        assertThat(summary.getDiagnostics())
                .anySatisfy(
                        d -> {
                            assertThat(d.getCode()).isEqualTo(ConverterDiagnostic.MALFORMED_RECORD);
                            assertThat(d.getFilePath()).isEqualTo(log.toString());
                        });
        exporter.shutdown();
    }

    @Test
    @DisplayName("A directory argument is expanded to its events-*.log files")
    void testDirectoryDiscovery(@TempDir Path tempDir) throws Exception {
        Files.write(
                tempDir.resolve("events-job-task-0.log"),
                List.of(
                        "{\"timestamp\":\"2026-01-15T10:30:00Z\",\"inputRunId\":\"run-1\","
                                + "\"executionId\":\"act-1\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"eventType\":\"_execution_started_event\","
                                + "\"status\":\"started\"}"));
        Files.write(
                tempDir.resolve("events-job-task-1.log"),
                List.of(
                        "{\"timestamp\":\"2026-01-15T10:30:01Z\",\"inputRunId\":\"run-2\","
                                + "\"executionId\":\"act-2\",\"entityType\":\"action\","
                                + "\"entityName\":\"review\","
                                + "\"eventType\":\"_execution_started_event\","
                                + "\"status\":\"started\"}"));
        // Not matching the FileEventLogger naming contract: ignored during discovery.
        Files.write(tempDir.resolve("other.txt"), List.of("not a log"));

        CollectingSpanExporter collector = new CollectingSpanExporter();
        EventLogOTelExporter exporter =
                EventLogOTelExporter.builder()
                        .setServiceName("test-job")
                        .setSpanExporter(collector)
                        .build();

        EventLogOTelExporter.ExportSummary summary = exporter.exportFiles(List.of(tempDir));

        assertThat(summary.getRecordsRead()).isEqualTo(2);
        // Two runs, each with a root span and one (incomplete) action span.
        assertThat(summary.getSpansExported()).isEqualTo(4);
        exporter.shutdown();
    }

    @Test
    @DisplayName("Rejects unsupported OTLP protocols at build time")
    void testUnsupportedProtocol() {
        try {
            EventLogOTelExporter.builder().setProtocol("carrier-pigeon").build();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("carrier-pigeon");
        }
    }
}
