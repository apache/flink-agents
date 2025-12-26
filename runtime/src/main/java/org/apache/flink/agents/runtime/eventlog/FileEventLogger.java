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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventFilter;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A file-based event logger that logs events to files with structured names in a flat directory.
 *
 * <p>This logger creates uniquely named log files for each subtask using a structured naming
 * convention that includes job ID, task name, and subtask ID. This approach aligns with Flink's
 * logging conventions and ensures no file conflicts in multi-TaskManager deployments. Events are
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
 *   ├── events-{jobId}-{taskName}-{subtaskId}.log
 *   ├── events-{jobId}-{taskName}-{subtaskId}.log
 *   └── events-{jobId}-{taskName}-{subtaskId}.log
 * </pre>
 *
 * <p>For example:
 *
 * <pre>
 * /tmp/flink-agents/
 *   ├── events-abc123-action-execute-operator-0.log
 *   ├── events-abc123-action-execute-operator-1.log
 *   └── events-def456-action-execute-operator-2.log
 * </pre>
 */
public class FileEventLogger implements EventLogger {
    public static final String BASE_LOG_DIR_PROPERTY_KEY = "baseLogDir";
    // The default base log directory if not specified in the configuration
    private static final String DEFAULT_BASE_LOG_DIR =
            Paths.get(System.getProperty("java.io.tmpdir"), "flink-agents").toString();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventLoggerConfig config;
    private final EventFilter eventFilter;
    private PrintWriter writer;

    public FileEventLogger(EventLoggerConfig config) {
        this.config = config;
        this.eventFilter = config.getEventFilter();
    }

    @Override
    public void open(EventLoggerOpenParams params) throws Exception {
        String logFilePath = generateSubTaskLogFilePath(params);
        // Create base directory if it doesn't exist
        Path logPath = Paths.get(logFilePath).getParent();
        if (!Files.exists(logPath)) {
            Files.createDirectories(logPath);
        }
        // Create writer in append mode
        writer = new PrintWriter(new BufferedWriter(new FileWriter(logFilePath, true)));
    }

    private String generateSubTaskLogFilePath(EventLoggerOpenParams params) {
        // Get base log directory from properties
        String baseLogDir =
                (String)
                        config.getProperties()
                                .getOrDefault(BASE_LOG_DIR_PROPERTY_KEY, DEFAULT_BASE_LOG_DIR);
        String jobId = params.getRuntimeContext().getJobInfo().getJobId().toString();
        String taskName =
                params.getRuntimeContext()
                        .getTaskInfo()
                        .getTaskName()
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
        int subTaskId = params.getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
        String fileName = String.format("events-%s-%s-%d.log", jobId, taskName, subTaskId);
        return Paths.get(baseLogDir, fileName).toString();
    }

    @Override
    public void append(EventContext context, Event event) throws Exception {
        if (writer == null) {
            throw new IllegalStateException("FileEventLogger not initialized. Call open() first.");
        }

        // Apply event filter
        if (!eventFilter.accept(event, context)) {
            return; // Skip this event
        }

        EventLogRecord record = new EventLogRecord(context, event);
        // All events should be JSON serializable, since we check it when sending events to context:
        // RunnerContextImpl.sendEvent
        writer.println(MAPPER.writeValueAsString(record));
    }

    @Override
    public void flush() throws Exception {
        if (writer == null) {
            throw new IllegalStateException("FileEventLogger not initialized. Call open() first.");
        }
        // Flush the writer to ensure all data is written to the file
        writer.flush();
    }

    @Override
    public void close() throws Exception {
        if (writer != null) {
            flush();
            writer.close();
        }
    }
}
