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

package org.apache.flink.agents.runtime.logger;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventFilter;
import org.apache.flink.agents.api.logger.EventLogRecord;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A file-based event logger that logs events to a file specific to each subtask.
 *
 * <p>This logger creates a unique directory structure for each subtask based on the job name and
 * subtask ID, ensuring no file conflicts in multi-TaskManager deployments. Events are appended to a
 * log file named "events.log" within that directory in JSON Lines format.
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
 * <p>The logger creates the following directory structure:
 *
 * <pre>
 * {baseLogDir}/
 *   └── {jobId}/
 *       ├── 0/
 *       │   └── events.log    (subtask 0)
 *       ├── 1/
 *       │   └── events.log    (subtask 1)
 *       └── 2/
 *           └── events.log    (subtask 2)
 * </pre>
 */
public class FileEventLogger implements EventLogger {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileEventLoggerConfig config;
    private final EventFilter eventFilter;
    private PrintWriter writer;

    public FileEventLogger(FileEventLoggerConfig config) {
        this.config = config;
        this.eventFilter = config.getEventFilter();
    }

    @Override
    public void open(EventLoggerOpenParams params) throws Exception {
        String logFilePath = generateSubTaskLogFilePath(params);
        // Create directory structure
        Path logPath = Paths.get(logFilePath).getParent();
        if (!Files.exists(logPath)) {
            Files.createDirectories(logPath);
        }
        // Create writer in append mode
        writer = new PrintWriter(new BufferedWriter(new FileWriter(logFilePath, true)));
    }

    private String generateSubTaskLogFilePath(EventLoggerOpenParams params) {
        String baseLogDir = config.getBaseEventLogDir();
        String jobId = params.getRuntimeContext().getJobInfo().getJobId().toString();
        int subTaskId = params.getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();
        return String.format("%s/%s/%d/events.log", baseLogDir, jobId, subTaskId);
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
