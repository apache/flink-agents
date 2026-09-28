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

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Exports Agent Trace Event Logs as OpenTelemetry GenAI traces.
 *
 * <p>This is the out-of-band exporter agreed in the Agent Trace design discussions: it reads the
 * Event Log written by the File/SLF4J event loggers (with {@code event-log.trace.enabled: true}),
 * assembles spans per {@link AgentTraceSpans}, and pushes them to any OTLP endpoint. Running out of
 * band means zero impact on the Flink job, and it works retroactively on logs from already finished
 * runs. Because span/trace ids are derived deterministically from the framework ids, exporting the
 * same log twice is idempotent on the backend.
 *
 * <p>Input files are streams of JSON objects — the JSONL written by the File Event Logger, and also
 * its pretty-printed variant (the parser consumes concatenated JSON objects regardless of line
 * breaks).
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * EventLogOTelExporter exporter =
 *     EventLogOTelExporter.builder()
 *             .setEndpoint("http://localhost:4317")
 *             .setProtocol("grpc")
 *             .setServiceName("my-agent-job")
 *             .build();
 * exporter.exportFiles(List.of(Path.of("/tmp/flink-agents/events-<jobId>-<task>-0.log")));
 * exporter.shutdown();
 * }</pre>
 */
public class EventLogOTelExporter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventLogOTelExporter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Per-file cap on malformed-record diagnostics, guarding against a stuck parser. */
    private static final int MAX_MALFORMED_PER_FILE = 1000;

    /** Default spans per OTLP request, matching the SDK {@code BatchSpanProcessor} default. */
    static final int DEFAULT_MAX_EXPORT_BATCH_SIZE = 512;

    /** How long each OTLP request may take before the export is reported as failed. */
    private static final long BATCH_EXPORT_TIMEOUT_SECONDS = 30;

    private final AgentTraceSpans assembler;
    private final SpanExporter spanExporter;
    private final int maxExportBatchSize;

    EventLogOTelExporter(
            AgentTraceSpans assembler, SpanExporter spanExporter, int maxExportBatchSize) {
        this.assembler = assembler;
        this.spanExporter = spanExporter;
        this.maxExportBatchSize = maxExportBatchSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads the given Event Log files and exports the assembled spans.
     *
     * <p>A directory argument is expanded to the {@code events-*.log} files it contains (the
     * FileEventLogger naming contract, matching {@code trace_tree.py}); a file argument is consumed
     * as-is regardless of its name, so records collected from any Event Log sink work once they are
     * on disk. Completeness of a multi-subtask file set is the caller's responsibility under the
     * batch contract; the summary reports what was read.
     */
    public ExportSummary exportFiles(List<Path> files) throws IOException {
        List<TraceRecord> records = new ArrayList<>();
        List<ConverterDiagnostic> diagnostics = new ArrayList<>();
        for (Path file : expandDirectories(files)) {
            try (InputStream in = Files.newInputStream(file);
                    MappingIterator<TraceRecord> it =
                            MAPPER.readerFor(TraceRecord.class).readValues(in)) {
                int malformedInFile = 0;
                boolean fileAborted = false;
                while (!fileAborted) {
                    try {
                        if (!it.hasNext()) {
                            break;
                        }
                        records.add(it.next());
                    } catch (RuntimeException e) {
                        // A malformed record must not abort the whole export. Value-level errors
                        // allow the iterator to continue; a structurally broken stream (or a
                        // parser that stops making progress) aborts just this file, with the
                        // diagnostic naming it either way.
                        diagnostics.add(
                                new ConverterDiagnostic(
                                        ConverterDiagnostic.MALFORMED_RECORD,
                                        null,
                                        "Could not decode an Event Log record: " + e.getMessage(),
                                        file.toString()));
                        malformedInFile++;
                        fileAborted = malformedInFile >= MAX_MALFORMED_PER_FILE || !canContinue(it);
                    }
                }
            }
        }
        return export(records, diagnostics);
    }

    private static boolean canContinue(MappingIterator<TraceRecord> it) {
        try {
            // Probe the iterator: a value-level bind error leaves it usable, a structurally
            // broken stream makes hasNext() itself throw.
            it.hasNext();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<Path> expandDirectories(List<Path> paths) throws IOException {
        List<Path> expanded = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                List<Path> discovered = new ArrayList<>();
                try (DirectoryStream<Path> stream =
                        Files.newDirectoryStream(path, "events-*.log")) {
                    stream.forEach(discovered::add);
                }
                Collections.sort(discovered);
                expanded.addAll(discovered);
            } else {
                expanded.add(path);
            }
        }
        return expanded;
    }

    /** Assembles and exports spans from already-parsed records. */
    public ExportSummary exportRecords(List<TraceRecord> records) {
        return export(records, new ArrayList<>());
    }

    private ExportSummary export(List<TraceRecord> records, List<ConverterDiagnostic> diagnostics) {
        List<SpanData> spans = assembler.assemble(records, diagnostics);
        for (ConverterDiagnostic diagnostic : diagnostics) {
            LOG.warn("{}", diagnostic);
        }
        // Each OTLP exporter marshals a whole collection into one request, and receivers cap the
        // request size (4 MiB by default for gRPC), so the spans go out in bounded batches.
        int exported = 0;
        while (exported < spans.size()) {
            List<SpanData> batch =
                    spans.subList(exported, Math.min(exported + maxExportBatchSize, spans.size()));
            CompletableResultCode result = spanExporter.export(batch);
            result.join(BATCH_EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!result.isSuccess()) {
                throw new ExportFailedException(exported, spans.size());
            }
            exported += batch.size();
        }
        return new ExportSummary(records.size(), exported, diagnostics);
    }

    @Override
    public void close() {
        shutdown();
    }

    /** Flushes and shuts down the underlying OTLP exporter. */
    public void shutdown() {
        spanExporter.flush().join(10, TimeUnit.SECONDS);
        spanExporter.shutdown().join(10, TimeUnit.SECONDS);
    }

    /**
     * Thrown when an OTLP request fails or times out. Earlier batches have already been delivered;
     * re-running the export is safe because span and trace ids are deterministic.
     */
    public static final class ExportFailedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final int spansExported;
        private final int spansTotal;

        ExportFailedException(int spansExported, int spansTotal) {
            super(
                    "OTLP export did not complete successfully: exported "
                            + spansExported
                            + " of "
                            + spansTotal
                            + " spans before a batch failed.");
            this.spansExported = spansExported;
            this.spansTotal = spansTotal;
        }

        /** Spans delivered by the batches that completed before the failure. */
        public int getSpansExported() {
            return spansExported;
        }

        public int getSpansTotal() {
            return spansTotal;
        }
    }

    /** Counters and diagnostics describing one export invocation. */
    public static final class ExportSummary {
        private final int recordsRead;
        private final int spansExported;
        private final List<ConverterDiagnostic> diagnostics;

        ExportSummary(int recordsRead, int spansExported, List<ConverterDiagnostic> diagnostics) {
            this.recordsRead = recordsRead;
            this.spansExported = spansExported;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public int getRecordsRead() {
            return recordsRead;
        }

        public int getSpansExported() {
            return spansExported;
        }

        /** Machine-readable diagnostics, per the {@code trace_tree.py} warning model. */
        public List<ConverterDiagnostic> getDiagnostics() {
            return diagnostics;
        }

        @Override
        public String toString() {
            return "read "
                    + recordsRead
                    + " records, exported "
                    + spansExported
                    + " spans, "
                    + diagnostics.size()
                    + " diagnostics";
        }
    }

    /** Builder for {@link EventLogOTelExporter}. */
    public static final class Builder {
        private String endpoint = "http://localhost:4317";
        private String protocol = "grpc";
        private String serviceName = "flink-agents";
        private int maxExportBatchSize = DEFAULT_MAX_EXPORT_BATCH_SIZE;
        private boolean businessKeyAsConversationId;
        private SpanExporter spanExporter;

        /** OTLP endpoint, e.g. {@code http://localhost:4317} (grpc) or {@code .../v1/traces}. */
        public Builder setEndpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** OTLP transport protocol: {@code grpc} (default) or {@code http/protobuf}. */
        public Builder setProtocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        /** Value of the {@code service.name} resource attribute; defaults to "flink-agents". */
        public Builder setServiceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /** Maximum spans per OTLP request; defaults to 512. */
        public Builder setMaxExportBatchSize(int maxExportBatchSize) {
            if (maxExportBatchSize <= 0) {
                throw new IllegalArgumentException(
                        "The export batch size must be positive, got " + maxExportBatchSize + ".");
            }
            this.maxExportBatchSize = maxExportBatchSize;
            return this;
        }

        /**
         * Whether to also report {@code businessKey} as {@code gen_ai.conversation.id}, for
         * pipelines keyed by conversation. Off by default; the key is always exported as {@code
         * flink_agents.business_key}.
         */
        public Builder setBusinessKeyAsConversationId(boolean businessKeyAsConversationId) {
            this.businessKeyAsConversationId = businessKeyAsConversationId;
            return this;
        }

        /** Overrides the underlying exporter; intended for tests. */
        public Builder setSpanExporter(SpanExporter spanExporter) {
            this.spanExporter = spanExporter;
            return this;
        }

        public EventLogOTelExporter build() {
            SpanExporter exporter = spanExporter;
            if (exporter == null) {
                if ("grpc".equalsIgnoreCase(protocol)) {
                    exporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
                } else if ("http/protobuf".equalsIgnoreCase(protocol)
                        || "http".equalsIgnoreCase(protocol)) {
                    exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported OTLP protocol: "
                                    + protocol
                                    + " (expected 'grpc' or 'http/protobuf').");
                }
            }
            return new EventLogOTelExporter(
                    new AgentTraceSpans(serviceName, businessKeyAsConversationId),
                    exporter,
                    maxExportBatchSize);
        }
    }

    /**
     * Command-line entry point.
     *
     * <p>Usage: {@code EventLogOTelExporter [--endpoint URL] [--protocol grpc|http/protobuf]
     * [--service-name NAME] [--batch-size N] [--business-key-as-conversation-id] eventLogFile...}
     */
    public static void main(String[] args) throws IOException {
        Builder builder = builder();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            boolean isFlag =
                    arg.equals("--endpoint")
                            || arg.equals("--protocol")
                            || arg.equals("--service-name")
                            || arg.equals("--batch-size");
            if (isFlag && i + 1 >= args.length) {
                exitWithUsage("Missing value for " + arg + ".");
            }
            switch (arg) {
                case "--endpoint":
                    builder.setEndpoint(args[++i]);
                    break;
                case "--protocol":
                    builder.setProtocol(args[++i]);
                    break;
                case "--service-name":
                    builder.setServiceName(args[++i]);
                    break;
                case "--batch-size":
                    builder.setMaxExportBatchSize(parseBatchSize(args[++i]));
                    break;
                case "--business-key-as-conversation-id":
                    builder.setBusinessKeyAsConversationId(true);
                    break;
                default:
                    files.add(Path.of(arg));
            }
        }
        if (files.isEmpty()) {
            exitWithUsage("No Event Log file or directory given.");
        }
        try (EventLogOTelExporter exporter = builder.build()) {
            ExportSummary summary = exporter.exportFiles(files);
            System.out.println(summary);
            for (ConverterDiagnostic diagnostic : summary.getDiagnostics()) {
                System.err.println(diagnostic);
            }
        }
    }

    private static int parseBatchSize(String value) {
        try {
            int batchSize = Integer.parseInt(value);
            if (batchSize > 0) {
                return batchSize;
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }
        exitWithUsage("--batch-size needs a positive integer, got '" + value + "'.");
        return DEFAULT_MAX_EXPORT_BATCH_SIZE;
    }

    private static void exitWithUsage(String problem) {
        System.err.println(problem);
        System.err.println(
                "Usage: EventLogOTelExporter [--endpoint URL] [--protocol grpc|http/protobuf]"
                        + " [--service-name NAME] [--batch-size N]"
                        + " [--business-key-as-conversation-id] eventLogFile...");
        System.exit(2);
    }
}
