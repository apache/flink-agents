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

package org.apache.flink.agents.runtime.eventlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventFilter;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An SLF4J-based event logger that outputs events through a dedicated SLF4J logger.
 *
 * <p>This logger writes event log records as JSON to a dedicated SLF4J logger named {@value
 * #EVENT_LOGGER_NAME}. Events are automatically routed to a separate file in Flink's log directory,
 * making them visible in Flink's Web UI "Logs" tab.
 *
 * <p>On {@link #open}, the logger automatically configures log4j2 to write event logs to a separate
 * file (derived from Flink's {@code log.file} system property). No manual log4j2 configuration is
 * required.
 *
 * <p>Unlike {@link FileEventLogger}, which creates a separate log file per subtask, this logger
 * writes all events from a TaskManager to the same log destination. To distinguish events from
 * different subtasks, each JSON record includes {@code jobId}, {@code taskName}, and {@code
 * subtaskId} fields.
 *
 * <h3>Thread Safety</h3>
 *
 * <p>This class is <strong>thread-safe at the Flink subtask level</strong>, following the same
 * guarantees as {@link FileEventLogger}. Each subtask instance gets its own logger instance with
 * its own subtask context fields.
 */
public class Slf4jEventLogger implements EventLogger {
    public static final String PRETTY_PRINT_PROPERTY_KEY = "prettyPrint";

    /** Dedicated logger name for event log output. */
    public static final String EVENT_LOGGER_NAME = "org.apache.flink.agents.EventLog";

    private static final String EVENT_LOG_APPENDER_NAME = "FlinkAgentsEventLogAppender";

    private static final Logger EVENT_LOG = LoggerFactory.getLogger(EVENT_LOGGER_NAME);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventLoggerConfig config;
    private final EventFilter eventFilter;
    private boolean prettyPrint;
    private String jobId;
    private String taskName;
    private int subtaskId;

    public Slf4jEventLogger(EventLoggerConfig config) {
        this.config = config;
        this.eventFilter = config.getEventFilter();
    }

    @Override
    public void open(EventLoggerOpenParams params) throws Exception {
        prettyPrint =
                (Boolean) config.getProperties().getOrDefault(PRETTY_PRINT_PROPERTY_KEY, true);
        jobId = params.getRuntimeContext().getJobInfo().getJobId().toString();
        taskName = params.getRuntimeContext().getTaskInfo().getTaskName();
        subtaskId = params.getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
        ensureLog4j2AppenderConfigured();
    }

    @Override
    public void append(EventContext context, Event event) throws Exception {
        if (!eventFilter.accept(event, context)) {
            return;
        }

        EventLogRecord record = new EventLogRecord(context, event);
        JsonNode jsonNode = MAPPER.valueToTree(record);

        if (jsonNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) jsonNode;
            objectNode.put("jobId", jobId);
            objectNode.put("taskName", taskName);
            objectNode.put("subtaskId", subtaskId);
        }

        String json =
                prettyPrint
                        ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode)
                        : MAPPER.writeValueAsString(jsonNode);
        EVENT_LOG.info(json);
    }

    @Override
    public void flush() throws Exception {
        // No-op: SLF4J/log4j2 handles flushing
    }

    @Override
    public void close() throws Exception {
        // No-op: SLF4J/log4j2 manages logger lifecycle
    }

    /**
     * Configures the log4j2 event log appender programmatically.
     *
     * <p>This method creates a dedicated file appender that writes to {@code
     * {log.file}.event-log.log} in the same directory as Flink's main log file. If the appender has
     * already been configured (e.g., by a previous subtask on the same TaskManager), this method is
     * a no-op.
     */
    private static synchronized void ensureLog4j2AppenderConfigured() {
        try {
            LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = loggerContext.getConfiguration();
            LoggerConfig loggerConfig = configuration.getLoggerConfig(EVENT_LOGGER_NAME);

            // If the appender has already been configured, skip.
            if (loggerConfig.getName().equals(EVENT_LOGGER_NAME)) {
                return;
            }

            // Derive event log file path from Flink's log.file system property
            String logFile = System.getProperty("log.file");
            if (logFile == null || logFile.isEmpty()) {
                // Not running in a Flink environment with log.file set,
                // fall back to root logger (events will go to main log)
                return;
            }

            String eventLogFile = logFile + ".event-log.log";

            // Create a file appender with %msg%n pattern (JSON only, no log metadata)
            PatternLayout layout = PatternLayout.newBuilder().withPattern("%msg%n").build();

            FileAppender appender =
                    FileAppender.newBuilder()
                            .setName(EVENT_LOG_APPENDER_NAME)
                            .withFileName(eventLogFile)
                            .withAppend(true)
                            .setLayout(layout)
                            .build();
            appender.start();
            configuration.addAppender(appender);

            // Create a dedicated logger config with additivity=false
            LoggerConfig eventLoggerConfig = new LoggerConfig(EVENT_LOGGER_NAME, Level.INFO, false);
            eventLoggerConfig.addAppender(appender, Level.INFO, null);
            configuration.addLogger(EVENT_LOGGER_NAME, eventLoggerConfig);

            loggerContext.updateLoggers();
        } catch (Exception e) {
            // If programmatic configuration fails (e.g., not using log4j2),
            // fall back silently — events will go to the root logger.
            LoggerFactory.getLogger(Slf4jEventLogger.class)
                    .warn(
                            "Failed to auto-configure event log appender, "
                                    + "events will be logged to the root logger.",
                            e);
        }
    }
}
