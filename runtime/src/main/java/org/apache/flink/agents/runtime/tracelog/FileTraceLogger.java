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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.TraceLogDetail;
import org.apache.flink.agents.api.logger.TraceLogger;
import org.apache.flink.agents.api.logger.TraceLoggerConfig;
import org.apache.flink.agents.api.logger.TraceLoggerOpenParams;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.metrics.Counter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * A file-based trace logger that writes trace records to files with structured names in a flat
 * directory.
 *
 * <p>This logger creates uniquely named log files for each subtask using a structured naming
 * convention that includes job ID, task name, and subtask ID. This approach aligns with Flink's
 * logging conventions and ensures no file conflicts in multi-TaskManager deployments. Records are
 * appended to log files in JSON Lines format.
 *
 * <h3>Thread Safety</h3>
 *
 * <p>This class is <strong>thread-safe at the Flink subtask level</strong>. Flink's execution model
 * guarantees that each subtask instance processes events in a single-threaded manner within the
 * operator's mailbox thread. This means:
 *
 * <ul>
 *   <li>No synchronization is needed for concurrent access within a subtask
 *   <li>Each subtask instance gets its own logger instance and unique log file
 *   <li>Multiple subtasks can run concurrently without file conflicts
 * </ul>
 *
 * <h3>File Structure</h3>
 *
 * <p>The logger creates log files in a flat directory structure with structured names that align
 * with Flink's logging conventions:
 *
 * <pre>
 * {baseLogDir}/
 *   ├── traces-{jobId}-{taskName}-{subtaskId}.log
 *   ├── traces-{jobId}-{taskName}-{subtaskId}.log
 *   └── traces-{jobId}-{taskName}-{subtaskId}.log
 * </pre>
 *
 * <p>For example:
 *
 * <pre>
 * /tmp/flink-agents/
 *   ├── traces-abc123-action-execute-operator-0.log
 *   ├── traces-abc123-action-execute-operator-1.log
 *   └── traces-def456-action-execute-operator-2.log
 * </pre>
 */
public class FileTraceLogger implements TraceLogger {
    // The default base log directory if not specified in the configuration
    private static final String DEFAULT_BASE_LOG_DIR =
            Paths.get(System.getProperty("java.io.tmpdir"), "flink-agents").toString();

    // Trace-Log-only mapper: chat messages are logged through their sanitized projection
    // (media metadata instead of payload bytes) at every detail setting.
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(ChatMessageTraceLogSerializer.module());

    private final TraceLoggerConfig config;
    private boolean prettyPrint;
    private PrintWriter writer;
    private JsonTruncator truncator;
    private Counter truncatedRecordsCounter;

    public FileTraceLogger(TraceLoggerConfig config) {
        this.config = config;
    }

    @Override
    public void open(TraceLoggerOpenParams params) throws Exception {
        // The full agent config is the single source of truth for all logger settings.
        @SuppressWarnings("unchecked")
        Map<String, Object> agentConfig =
                (Map<String, Object>)
                        config.getProperties()
                                .getOrDefault(
                                        TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY,
                                        Collections.emptyMap());

        String baseLogDir =
                (String)
                        agentConfig.getOrDefault(
                                AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR.getKey(),
                                DEFAULT_BASE_LOG_DIR);
        String logFilePath = generateSubTaskLogFilePath(params, baseLogDir);
        // Create base directory if it doesn't exist
        Path logPath = Paths.get(logFilePath).getParent();
        if (!Files.exists(logPath)) {
            Files.createDirectories(logPath);
        }
        // Create writer in append mode
        writer = new PrintWriter(new BufferedWriter(new FileWriter(logFilePath, true)));
        prettyPrint =
                (Boolean)
                        agentConfig.getOrDefault(
                                AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT.getKey(),
                                AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT.getDefaultValue());

        int maxStringLength =
                getIntFromConfig(
                        agentConfig,
                        AgentConfigOptions.TRACE_LOG_MAX_STRING_LENGTH.getKey(),
                        AgentConfigOptions.TRACE_LOG_MAX_STRING_LENGTH.getDefaultValue());
        int maxArrayElements =
                getIntFromConfig(
                        agentConfig,
                        AgentConfigOptions.TRACE_LOG_MAX_ARRAY_ELEMENTS.getKey(),
                        AgentConfigOptions.TRACE_LOG_MAX_ARRAY_ELEMENTS.getDefaultValue());
        int maxDepth =
                getIntFromConfig(
                        agentConfig,
                        AgentConfigOptions.TRACE_LOG_MAX_DEPTH.getKey(),
                        AgentConfigOptions.TRACE_LOG_MAX_DEPTH.getDefaultValue());
        this.truncator = new JsonTruncator(maxStringLength, maxArrayElements, maxDepth);
    }

    private static int getIntFromConfig(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String generateSubTaskLogFilePath(TraceLoggerOpenParams params, String baseLogDir) {
        String jobId = params.getRuntimeContext().getJobInfo().getJobId().toString();
        String taskName =
                params.getRuntimeContext()
                        .getTaskInfo()
                        .getTaskName()
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
        int subTaskId = params.getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
        String fileName = String.format("traces-%s-%s-%d.log", jobId, taskName, subTaskId);
        return Paths.get(baseLogDir, fileName).toString();
    }

    @Override
    public void append(TraceRecord record, TraceLogDetail detail) throws Exception {
        if (writer == null) {
            throw new IllegalStateException("FileTraceLogger not initialized. Call open() first.");
        }
        if (detail == TraceLogDetail.OFF) {
            return;
        }

        JsonNode tree = MAPPER.valueToTree(record);
        if (!(tree instanceof ObjectNode)) {
            throw new IllegalStateException(
                    "TraceRecord must serialize to a JSON object, but was: " + tree.getNodeType());
        }
        ObjectNode rootNode = (ObjectNode) tree;

        // Truncate record attributes at STANDARD detail.
        if (detail == TraceLogDetail.STANDARD && truncator != null) {
            JsonNode attributesNode = rootNode.get("attributes");
            if (attributesNode instanceof ObjectNode) {
                boolean truncated = truncator.truncate((ObjectNode) attributesNode);
                if (truncated && truncatedRecordsCounter != null) {
                    truncatedRecordsCounter.inc();
                }
            }
        }

        ObjectNode ordered = withDetail(rootNode, detail);

        String json =
                prettyPrint
                        ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ordered)
                        : MAPPER.writeValueAsString(ordered);
        writer.println(json);
    }

    private static ObjectNode withDetail(ObjectNode rootNode, TraceLogDetail detail) {
        ObjectNode ordered = MAPPER.createObjectNode();
        ordered.set("timestamp", rootNode.get("timestamp"));
        ordered.put("detail", detail.name());
        Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"timestamp".equals(field.getKey())) {
                ordered.set(field.getKey(), field.getValue());
            }
        }
        return ordered;
    }

    @Override
    public void flush() throws Exception {
        if (writer == null) {
            throw new IllegalStateException("FileTraceLogger not initialized. Call open() first.");
        }
        // checkError flushes first and exposes I/O failures otherwise swallowed by PrintWriter.
        if (writer.checkError()) {
            throw new IOException("Failed to flush the Trace Log file.");
        }
    }

    /**
     * Sets the counter for tracking truncated records. Called by the operator after metrics are
     * initialized.
     *
     * @param counter the counter to increment when records are truncated
     */
    public void setTruncatedRecordsCounter(Counter counter) {
        this.truncatedRecordsCounter = counter;
    }

    @Override
    public void close() throws Exception {
        if (writer != null) {
            // PrintWriter.close() flushes before releasing the underlying writer.
            writer.close();
        }
    }
}
