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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class Slf4jEventLoggerTest {

    @Mock private StreamingRuntimeContext runtimeContext;

    @Mock private JobInfo jobInfo;

    @Mock private TaskInfo taskInfo;

    private Slf4jEventLogger logger;
    private EventLoggerOpenParams openParams;
    private ObjectMapper objectMapper;
    private TestAppender testAppender;

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

        openParams = new EventLoggerOpenParams(runtimeContext);

        // Set up log4j2 test appender to capture event log output
        testAppender = new TestAppender("TestSlf4jAppender");
        testAppender.start();

        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration config = loggerContext.getConfiguration();
        config.addAppender(testAppender);

        LoggerConfig loggerConfig = config.getLoggerConfig(Slf4jEventLogger.EVENT_LOGGER_NAME);
        if (!loggerConfig.getName().equals(Slf4jEventLogger.EVENT_LOGGER_NAME)) {
            loggerConfig =
                    new LoggerConfig(
                            Slf4jEventLogger.EVENT_LOGGER_NAME,
                            org.apache.logging.log4j.Level.INFO,
                            false);
            config.addLogger(Slf4jEventLogger.EVENT_LOGGER_NAME, loggerConfig);
        }
        loggerConfig.addAppender(testAppender, org.apache.logging.log4j.Level.INFO, null);
        loggerContext.updateLoggers();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (logger != null) {
            logger.close();
        }
        if (testAppender != null) {
            testAppender.stop();
            LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
            Configuration config = loggerContext.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig(Slf4jEventLogger.EVENT_LOGGER_NAME);
            loggerConfig.removeAppender(testAppender.getName());
            loggerContext.updateLoggers();
        }
    }

    @Test
    void testAppendWritesJsonWithSubtaskContext() throws Exception {
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType("slf4j").build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        EventContext context = new EventContext(inputEvent);

        logger.append(context, inputEvent);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size(), "Should have logged one message");

        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        // Verify subtask context fields
        assertEquals(testJobId.toString(), jsonNode.get("jobId").asText());
        assertEquals(testTaskName, jsonNode.get("taskName").asText());
        assertEquals(testSubTaskId, jsonNode.get("subtaskId").asInt());
        // Verify event content
        assertNotNull(jsonNode.get("timestamp"));
        assertNotNull(jsonNode.get("event"));
        assertEquals(
                "org.apache.flink.agents.api.InputEvent",
                jsonNode.get("event").get("eventType").asText());
    }

    @Test
    void testAppendMultipleEvents() throws Exception {
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType("slf4j").build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);

        List<String> messages = testAppender.getMessages();
        assertEquals(2, messages.size(), "Should have logged two messages");

        JsonNode inputJson = objectMapper.readTree(messages.get(0));
        assertEquals("input data", inputJson.get("event").get("input").asText());

        JsonNode outputJson = objectMapper.readTree(messages.get(1));
        assertEquals("output data", outputJson.get("event").get("output").asText());
    }

    @Test
    void testEventFilterRejectAll() throws Exception {
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType("slf4j")
                        .eventFilter(EventFilter.REJECT_ALL)
                        .build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        logger.append(new EventContext(inputEvent), inputEvent);

        List<String> messages = testAppender.getMessages();
        assertTrue(messages.isEmpty(), "No events should be logged with REJECT_ALL filter");
    }

    @Test
    void testEventFilterByEventType() throws Exception {
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType("slf4j")
                        .eventFilter(EventFilter.byEventType(InputEvent.class))
                        .build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        logger.append(new EventContext(inputEvent), inputEvent);
        logger.append(new EventContext(outputEvent), outputEvent);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size(), "Only InputEvent should be logged");

        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        assertEquals("input data", jsonNode.get("event").get("input").asText());
    }

    @Test
    void testDefaultPrettyPrintOutput() throws Exception {
        // Default config should produce pretty-printed JSON
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType("slf4j").build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        logger.append(new EventContext(inputEvent), inputEvent);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());

        String json = messages.get(0);
        assertTrue(json.contains("\n"), "Default output should be pretty-printed");
        assertDoesNotThrow(
                () -> objectMapper.readTree(json), "Pretty-printed output should be valid JSON");
    }

    @Test
    void testDisablePrettyPrint() throws Exception {
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType("slf4j")
                        .property(Slf4jEventLogger.PRETTY_PRINT_PROPERTY_KEY, false)
                        .build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        logger.append(new EventContext(inputEvent), inputEvent);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());

        String json = messages.get(0);
        assertFalse(json.contains("\n"), "Non-pretty output should be single line");
        assertDoesNotThrow(() -> objectMapper.readTree(json), "Output should be valid JSON");
    }

    @Test
    void testFlushAndCloseAreNoOps() throws Exception {
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType("slf4j").build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        assertDoesNotThrow(() -> logger.flush(), "flush() should not throw");
        assertDoesNotThrow(() -> logger.close(), "close() should not throw");
    }

    /** Custom test event class for testing polymorphic serialization. */
    public static class TestCustomEvent extends Event {
        private String customData;
        private int customNumber;

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

    /** A log4j2 appender that captures log messages for testing. */
    private static class TestAppender extends AbstractAppender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        protected TestAppender(String name) {
            super(name, null, PatternLayout.newBuilder().withPattern("%msg").build(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        public List<String> getMessages() {
            return messages;
        }
    }
}
