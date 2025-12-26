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
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventFilter;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobInfo;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class FileEventLoggerTest {

    @TempDir Path tempDir;

    @Mock private StreamingRuntimeContext runtimeContext;

    @Mock private JobInfo jobInfo;

    @Mock private TaskInfo taskInfo;

    private FileEventLogger logger;
    private EventLoggerConfig config;
    private EventLoggerOpenParams openParams;
    private ObjectMapper objectMapper;

    private final JobID testJobId = JobID.generate();
    private final String testTaskName = "action-execute-operator";
    private final int testSubTaskId = 0;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();

        // Configure mocks
        when(runtimeContext.getJobInfo()).thenReturn(jobInfo);
        when(runtimeContext.getTaskInfo()).thenReturn(taskInfo);
        when(jobInfo.getJobId()).thenReturn(testJobId);
        when(taskInfo.getTaskName()).thenReturn(testTaskName);
        when(taskInfo.getIndexOfThisSubtask()).thenReturn(testSubTaskId);

        // Create config and logger
        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property(FileEventLogger.BASE_LOG_DIR_PROPERTY_KEY, tempDir.toString())
                        .build();
        logger = new FileEventLogger(config);
        openParams = new EventLoggerOpenParams(runtimeContext);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (logger != null) {
            logger.close();
        }
    }

    @Test
    void testOpenCreatesLogFile() throws Exception {
        logger.open(openParams);

        Path expectedLogFile = getExpectedLogFilePath();
        assertTrue(Files.exists(expectedLogFile), "Log file should be created");
        assertTrue(Files.isRegularFile(expectedLogFile), "Path should be a regular file");
    }

    @Test
    void testAppendWritesJsonEvents() throws Exception {
        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("test input");
        EventContext context = new EventContext(inputEvent);

        logger.append(context, inputEvent);
        logger.flush();

        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size(), "Should have written one line");

        EventLogRecord deserializedRecord =
                objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertNotNull(deserializedRecord, "Deserialized record should not be null");
        assertNotNull(deserializedRecord.getContext(), "Deserialized context should not be null");
        assertNotNull(deserializedRecord.getEvent(), "Deserialized event should not be null");

        assertEquals(
                "org.apache.flink.agents.api.InputEvent",
                deserializedRecord.getContext().getEventType());
        assertInstanceOf(
                InputEvent.class,
                deserializedRecord.getEvent(),
                "Deserialized event should be InputEvent");
        assertEquals("test input", ((InputEvent) deserializedRecord.getEvent()).getInput());
    }

    @Test
    void testAppendMultipleEvents() throws Exception {
        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");
        EventContext inputContext = new EventContext(inputEvent);
        EventContext outputContext = new EventContext(outputEvent);

        logger.append(inputContext, inputEvent);
        logger.append(outputContext, outputEvent);
        logger.flush();

        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "Should have written two lines");

        // Verify first event (InputEvent) - deserialization
        EventLogRecord inputRecord = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertTrue(inputRecord.getEvent() instanceof InputEvent);
        assertEquals("input data", ((InputEvent) inputRecord.getEvent()).getInput());

        // Verify second event (OutputEvent) - deserialization
        EventLogRecord outputRecord = objectMapper.readValue(lines.get(1), EventLogRecord.class);
        assertTrue(outputRecord.getEvent() instanceof OutputEvent);
        assertEquals("output data", ((OutputEvent) outputRecord.getEvent()).getOutput());
    }

    @Test
    void testAppendWithCustomEvent() throws Exception {
        // Given
        logger.open(openParams);
        TestCustomEvent customEvent = new TestCustomEvent("custom data", 42);
        EventContext context = new EventContext(customEvent);

        // When
        logger.append(context, customEvent);
        logger.flush();

        // Then
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());

        // Verify JSON structure
        JsonNode jsonNode = objectMapper.readTree(lines.get(0));
        assertEquals(
                "org.apache.flink.agents.runtime.eventlog.FileEventLoggerTest$TestCustomEvent",
                jsonNode.get("context").get("eventType").asText());

        JsonNode eventNode = jsonNode.get("event");
        assertEquals("custom data", eventNode.get("customData").asText());
        assertEquals(42, eventNode.get("customNumber").asInt());

        // Verify deserialization works correctly
        EventLogRecord deserializedRecord =
                objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertNotNull(deserializedRecord);
        assertTrue(
                deserializedRecord.getEvent() instanceof TestCustomEvent,
                "Deserialized event should be TestCustomEvent");

        TestCustomEvent deserializedEvent = (TestCustomEvent) deserializedRecord.getEvent();
        assertEquals("custom data", deserializedEvent.getCustomData());
        assertEquals(42, deserializedEvent.getCustomNumber());
    }

    @Test
    void testAppendInAppendMode() throws Exception {
        // Given - first session
        logger.open(openParams);
        InputEvent event1 = new InputEvent("first event");
        EventContext context1 = new EventContext(event1);
        logger.append(context1, event1);
        logger.close();

        // When - second session (append mode)
        FileEventLogger secondLogger = new FileEventLogger(config);
        secondLogger.open(openParams);
        InputEvent event2 = new InputEvent("second event");
        EventContext context2 = new EventContext(event2);
        secondLogger.append(context2, event2);
        secondLogger.flush();
        secondLogger.close();

        // Then
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "Should have both events in append mode");

        // Verify JSON structure
        JsonNode firstEventJson = objectMapper.readTree(lines.get(0));
        assertEquals("first event", firstEventJson.get("event").get("input").asText());

        JsonNode secondEventJson = objectMapper.readTree(lines.get(1));
        assertEquals("second event", secondEventJson.get("event").get("input").asText());

        // Verify deserialization
        EventLogRecord firstRecord = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertTrue(firstRecord.getEvent() instanceof InputEvent);
        assertEquals("first event", ((InputEvent) firstRecord.getEvent()).getInput());

        EventLogRecord secondRecord = objectMapper.readValue(lines.get(1), EventLogRecord.class);
        assertTrue(secondRecord.getEvent() instanceof InputEvent);
        assertEquals("second event", ((InputEvent) secondRecord.getEvent()).getInput());
    }

    @Test
    void testMultipleSubTasks() throws Exception {
        // Given - subtask 0
        logger.open(openParams);
        InputEvent event1 = new InputEvent("subtask 0 event");
        EventContext context1 = new EventContext(event1);
        logger.append(context1, event1);
        logger.flush();

        // Given - subtask 1
        when(taskInfo.getIndexOfThisSubtask()).thenReturn(1);
        FileEventLogger logger2 = new FileEventLogger(config);
        EventLoggerOpenParams openParams2 = new EventLoggerOpenParams(runtimeContext);
        logger2.open(openParams2);
        InputEvent event2 = new InputEvent("subtask 1 event");
        EventContext context2 = new EventContext(event2);
        logger2.append(context2, event2);
        logger2.flush();
        logger2.close();

        // Then - verify separate files with structured names
        Path subtask0File =
                tempDir.resolve(
                        String.format(
                                "events-%s-%s-%d.log", testJobId.toString(), testTaskName, 0));
        Path subtask1File =
                tempDir.resolve(
                        String.format(
                                "events-%s-%s-%d.log", testJobId.toString(), testTaskName, 1));

        assertTrue(Files.exists(subtask0File), "Subtask 0 file should exist");
        assertTrue(Files.exists(subtask1File), "Subtask 1 file should exist");

        List<String> subtask0Lines = Files.readAllLines(subtask0File);
        List<String> subtask1Lines = Files.readAllLines(subtask1File);

        assertEquals(1, subtask0Lines.size());
        assertEquals(1, subtask1Lines.size());

        // Verify JSON structure
        JsonNode subtask0EventJson = objectMapper.readTree(subtask0Lines.get(0));
        JsonNode subtask1EventJson = objectMapper.readTree(subtask1Lines.get(0));

        assertEquals("subtask 0 event", subtask0EventJson.get("event").get("input").asText());
        assertEquals("subtask 1 event", subtask1EventJson.get("event").get("input").asText());

        // Verify deserialization
        EventLogRecord subtask0Record =
                objectMapper.readValue(subtask0Lines.get(0), EventLogRecord.class);
        assertTrue(subtask0Record.getEvent() instanceof InputEvent);
        assertEquals("subtask 0 event", ((InputEvent) subtask0Record.getEvent()).getInput());

        EventLogRecord subtask1Record =
                objectMapper.readValue(subtask1Lines.get(0), EventLogRecord.class);
        assertTrue(subtask1Record.getEvent() instanceof InputEvent);
        assertEquals("subtask 1 event", ((InputEvent) subtask1Record.getEvent()).getInput());
    }

    @Test
    void testEventFilterAcceptAll() throws Exception {
        // Given - config with ACCEPT_ALL filter (default behavior)
        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property("baseLogDir", tempDir.toString())
                        .eventFilter(EventFilter.ACCEPT_ALL)
                        .build();
        logger = new FileEventLogger(config);

        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        // When
        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);
        logger.flush();

        // Then - both events should be logged
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "Both events should be logged with ACCEPT_ALL filter");

        // Verify both events were deserialized correctly
        EventLogRecord inputRecord = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertInstanceOf(InputEvent.class, inputRecord.getEvent());

        EventLogRecord outputRecord = objectMapper.readValue(lines.get(1), EventLogRecord.class);
        assertInstanceOf(OutputEvent.class, outputRecord.getEvent());
    }

    @Test
    void testEventFilterRejectAll() throws Exception {
        // Given - config with REJECT_ALL filter
        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property("baseLogDir", tempDir.toString())
                        .eventFilter(EventFilter.REJECT_ALL)
                        .build();
        logger = new FileEventLogger(config);

        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        // When
        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);
        logger.flush();

        // Then - no events should be logged (file should not exist or be empty)
        Path logFile = getExpectedLogFilePath();
        assertTrue(
                !Files.exists(logFile) || Files.readAllLines(logFile).isEmpty(),
                "No events should be logged with REJECT_ALL filter");
    }

    @Test
    void testEventFilterByEventType() throws Exception {
        // Given - config with filter that only accepts InputEvents
        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property("baseLogDir", tempDir.toString())
                        .eventFilter(EventFilter.byEventType(InputEvent.class))
                        .build();
        logger = new FileEventLogger(config);

        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");
        TestCustomEvent customEvent = new TestCustomEvent("custom data", 42);

        // When
        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);
        logger.append(new EventContext(customEvent), customEvent);
        logger.flush();

        // Then - only InputEvent should be logged
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size(), "Only InputEvent should be logged");

        EventLogRecord record = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertInstanceOf(InputEvent.class, record.getEvent());
        assertEquals("input data", ((InputEvent) record.getEvent()).getInput());
    }

    @Test
    void testEventFilterByMultipleEventTypes() throws Exception {
        // Given - config with filter that accepts InputEvents and OutputEvents
        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property("baseLogDir", tempDir.toString())
                        .eventFilter(EventFilter.byEventType(InputEvent.class, OutputEvent.class))
                        .build();
        logger = new FileEventLogger(config);

        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");
        TestCustomEvent customEvent = new TestCustomEvent("custom data", 42);

        // When
        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);
        logger.append(new EventContext(customEvent), customEvent);
        logger.flush();

        // Then - InputEvent and OutputEvent should be logged, but not TestCustomEvent
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "InputEvent and OutputEvent should be logged");

        EventLogRecord inputRecord = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertInstanceOf(InputEvent.class, inputRecord.getEvent());
        assertEquals("input data", ((InputEvent) inputRecord.getEvent()).getInput());

        EventLogRecord outputRecord = objectMapper.readValue(lines.get(1), EventLogRecord.class);
        assertInstanceOf(OutputEvent.class, outputRecord.getEvent());
        assertEquals("output data", ((OutputEvent) outputRecord.getEvent()).getOutput());
    }

    @Test
    void testCustomEventFilter() throws Exception {
        // Given - config with custom filter that only accepts events with specific content
        EventFilter customFilter =
                (event, context) -> {
                    if (event instanceof InputEvent) {
                        return ((InputEvent) event).getInput().toString().contains("important");
                    }
                    return false;
                };

        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property("baseLogDir", tempDir.toString())
                        .eventFilter(customFilter)
                        .build();
        logger = new FileEventLogger(config);

        logger.open(openParams);
        InputEvent importantEvent = new InputEvent("important data");
        InputEvent regularEvent = new InputEvent("regular data");
        OutputEvent outputEvent = new OutputEvent("output data");

        // When
        logger.append(new EventContext(importantEvent), importantEvent);
        logger.append(new EventContext(regularEvent), regularEvent);
        logger.append(new EventContext(outputEvent), outputEvent);
        logger.flush();

        // Then - only the "important" InputEvent should be logged
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size(), "Only important InputEvent should be logged");

        EventLogRecord record = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertInstanceOf(InputEvent.class, record.getEvent());
        assertEquals("important data", ((InputEvent) record.getEvent()).getInput());
    }

    @Test
    void testDefaultEventFilterBehavior() throws Exception {
        // Given - config without explicit eventFilter (should default to ACCEPT_ALL)
        config =
                EventLoggerConfig.builder()
                        .loggerType("file")
                        .property("baseLogDir", tempDir.toString())
                        .build();
        logger = new FileEventLogger(config);

        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        // When
        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);
        logger.flush();

        // Then - both events should be logged (default ACCEPT_ALL behavior)
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "Both events should be logged with default filter");

        EventLogRecord inputRecord = objectMapper.readValue(lines.get(0), EventLogRecord.class);
        assertInstanceOf(InputEvent.class, inputRecord.getEvent());

        EventLogRecord outputRecord = objectMapper.readValue(lines.get(1), EventLogRecord.class);
        assertInstanceOf(OutputEvent.class, outputRecord.getEvent());
    }

    private Path getExpectedLogFilePath() {
        return tempDir.resolve(
                String.format(
                        "events-%s-%s-%d.log", testJobId.toString(), testTaskName, testSubTaskId));
    }

    /** Custom test event class for testing polymorphic serialization. */
    public static class TestCustomEvent extends Event {
        private String customData;
        private int customNumber;

        // Default constructor for Jackson
        public TestCustomEvent() {}

        public TestCustomEvent(String customData, int customNumber) {
            this.customData = customData;
            this.customNumber = customNumber;
        }

        public String getCustomData() {
            return customData;
        }

        public void setCustomData(String customData) {
            this.customData = customData;
        }

        public int getCustomNumber() {
            return customNumber;
        }

        public void setCustomNumber(int customNumber) {
            this.customNumber = customNumber;
        }
    }
}
