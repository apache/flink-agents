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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * An SLF4J-based trace logger that writes trace records through a dedicated SLF4J logger.
 *
 * <p>This logger writes trace log records as JSON to a dedicated SLF4J logger named {@value
 * #TRACE_LOGGER_NAME}. Records are automatically routed to a separate file in Flink's log
 * directory, making them visible in Flink's Web UI "Logs" tab.
 *
 * <p>On {@link #open}, the logger automatically configures log4j2 to write trace logs to a separate
 * file (derived from Flink's {@code log.file} system property). No manual log4j2 configuration is
 * required.
 *
 * <p>Unlike {@link FileTraceLogger}, which creates a separate log file per subtask, this logger
 * writes all trace records from a TaskManager to the same log destination. To distinguish records
 * from different subtasks, each JSON record includes {@code jobId}, {@code taskName}, and {@code
 * subtaskId} fields.
 *
 * <p>The caller supplies selected records and their recording detail. At {@link
 * TraceLogDetail#STANDARD}, attributes are truncated according to the configured limits;
 * identities, relationships, timestamps, and statuses remain complete.
 *
 * <h3>Thread Safety</h3>
 *
 * <p>This class is <strong>thread-safe at the Flink subtask level</strong>, following the same
 * guarantees as {@link FileTraceLogger}. Each subtask instance gets its own logger instance with
 * its own subtask context fields.
 */
public class Slf4jTraceLogger implements TraceLogger {
    /** Dedicated logger name for trace log output. */
    public static final String TRACE_LOGGER_NAME = "org.apache.flink.agents.TraceLog";

    private static final String TRACE_LOG_APPENDER_NAME = "FlinkAgentsTraceLogAppender";

    private static final Logger TRACE_LOG = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

    // Trace-Log-only mapper: chat messages are logged through their sanitized projection
    // (media metadata instead of payload bytes) at every detail setting.
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(ChatMessageTraceLogSerializer.module());

    private final TraceLoggerConfig config;
    private boolean prettyPrint;
    private String jobId;
    private String taskName;
    private int subtaskId;
    private JsonTruncator truncator;
    private Counter truncatedRecordsCounter;

    public Slf4jTraceLogger(TraceLoggerConfig config) {
        this.config = config;
    }

    @Override
    public void open(TraceLoggerOpenParams params) throws Exception {
        jobId = params.getRuntimeContext().getJobInfo().getJobId().toString();
        taskName = params.getRuntimeContext().getTaskInfo().getTaskName();
        subtaskId = params.getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();

        // The full agent config is the single source of truth for all logger settings
        // (mirrors FileTraceLogger).
        @SuppressWarnings("unchecked")
        Map<String, Object> agentConfig =
                (Map<String, Object>)
                        config.getProperties()
                                .getOrDefault(
                                        TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY,
                                        Collections.emptyMap());
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

        ensureLog4j2AppenderConfigured();
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

    @Override
    public void append(TraceRecord record, TraceLogDetail detail) throws Exception {
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

        ObjectNode ordered = withDetailAndSubtaskContext(rootNode, detail);

        String json =
                prettyPrint
                        ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ordered)
                        : MAPPER.writeValueAsString(ordered);
        TRACE_LOG.info(json);
    }

    private ObjectNode withDetailAndSubtaskContext(ObjectNode rootNode, TraceLogDetail detail) {
        ObjectNode ordered = MAPPER.createObjectNode();
        ordered.set("timestamp", rootNode.get("timestamp"));
        ordered.put("detail", detail.name());
        ordered.put("jobId", jobId);
        ordered.put("taskName", taskName);
        ordered.put("subtaskId", subtaskId);
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
        // No-op: SLF4J/log4j2 handles flushing
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
        // No-op: SLF4J/log4j2 manages logger lifecycle
    }

    /**
     * Configures the log4j2 trace log appender programmatically.
     *
     * <p>This method creates a dedicated file appender that writes to {@code
     * {log.file}.trace-log.log} in the same directory as Flink's main log file. If the appender has
     * already been configured (e.g., by a previous subtask on the same TaskManager), this method is
     * a no-op.
     */
    private static synchronized void ensureLog4j2AppenderConfigured() {
        try {
            LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = loggerContext.getConfiguration();
            LoggerConfig loggerConfig = configuration.getLoggerConfig(TRACE_LOGGER_NAME);

            // If the appender has already been configured, skip.
            if (loggerConfig.getName().equals(TRACE_LOGGER_NAME)) {
                return;
            }

            // Derive trace log file path from Flink's log.file system property
            String logFile = System.getProperty("log.file");
            if (logFile == null || logFile.isEmpty()) {
                // Not running in a Flink environment with log.file set,
                // fall back to root logger (records will go to main log)
                return;
            }

            String traceLogFile = logFile + ".trace-log.log";

            // Create a file appender with %msg%n pattern (JSON only, no log metadata)
            PatternLayout layout = PatternLayout.newBuilder().withPattern("%msg%n").build();

            FileAppender appender =
                    FileAppender.newBuilder()
                            .setName(TRACE_LOG_APPENDER_NAME)
                            .withFileName(traceLogFile)
                            .withAppend(true)
                            .setLayout(layout)
                            .build();
            appender.start();
            configuration.addAppender(appender);

            // Create a dedicated logger config with additivity=false
            LoggerConfig traceLoggerConfig = new LoggerConfig(TRACE_LOGGER_NAME, Level.INFO, false);
            traceLoggerConfig.addAppender(appender, Level.INFO, null);
            configuration.addLogger(TRACE_LOGGER_NAME, traceLoggerConfig);

            loggerContext.updateLoggers();
        } catch (Exception e) {
            // If programmatic configuration fails (e.g., not using log4j2),
            // fall back silently — records will go to the root logger.
            LoggerFactory.getLogger(Slf4jTraceLogger.class)
                    .warn(
                            "Failed to auto-configure trace log appender, "
                                    + "records will be logged to the root logger.",
                            e);
        }
    }
}
