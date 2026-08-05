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
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        ExecutionTraceContext context = null;

        append(inputEvent, context);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size(), "Should have logged one message");

        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        // Verify subtask context fields
        assertEquals(testJobId.toString(), jsonNode.get("jobId").asText());
        assertEquals(testTaskName, jsonNode.get("taskName").asText());
        assertEquals(testSubTaskId, jsonNode.get("subtaskId").asInt());
        // Verify event content
        assertNotNull(jsonNode.get("timestamp"));
        assertNotNull(jsonNode.get("eventId"));
        assertNotNull(jsonNode.get("eventAttributes"));
        assertEquals(InputEvent.EVENT_TYPE, jsonNode.get("eventType").asText());
    }

    @Test
    void testAppendMultipleEvents() throws Exception {
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        append(inputEvent, null);
        append(outputEvent, null);

        List<String> messages = testAppender.getMessages();
        assertEquals(2, messages.size(), "Should have logged two messages");

        JsonNode inputJson = objectMapper.readTree(messages.get(0));
        assertEquals("input data", inputJson.get("eventAttributes").get("input").asText());

        JsonNode outputJson = objectMapper.readTree(messages.get(1));
        assertEquals("output data", outputJson.get("eventAttributes").get("output").asText());
    }

    @Test
    void testRootLevelOffSkipsAllEvents() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put(AgentConfigOptions.EVENT_LOG_LEVEL.getKey(), "OFF");
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(EventLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                        .build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        append(inputEvent, null);

        List<String> messages = testAppender.getMessages();
        assertTrue(messages.isEmpty(), "No events should be logged when root level is OFF");
    }

    @Test
    void testPerEventTypeOffSkipsMatchingEvents() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("event-log.type." + InputEvent.EVENT_TYPE + ".level", "OFF");
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(EventLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                        .build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        append(inputEvent, null);
        append(outputEvent, null);

        List<String> messages = testAppender.getMessages();
        assertEquals(
                1, messages.size(), "Only OutputEvent should be logged when InputEvent is OFF");

        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        assertEquals("output data", jsonNode.get("eventAttributes").get("output").asText());
    }

    @Test
    void testLogLevelAppearsInJson() throws Exception {
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        append(inputEvent, null);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());
        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        assertNotNull(jsonNode.get("logLevel"), "logLevel field should be present in JSON output");
    }

    @Test
    void testDefaultIsNotPrettyPrinted() throws Exception {
        // Default config should produce single-line JSON, matching AgentConfigOptions.PRETTY_PRINT
        // default (false).
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        append(inputEvent, null);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());

        String json = messages.get(0);
        assertFalse(json.contains("\n"), "Default output should be single line");
        assertDoesNotThrow(() -> objectMapper.readTree(json), "Output should be valid JSON");
    }

    @Test
    void testEnablePrettyPrint() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put(AgentConfigOptions.PRETTY_PRINT.getKey(), true);
        EventLoggerConfig config =
                EventLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(EventLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                        .build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        append(inputEvent, null);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());

        String json = messages.get(0);
        assertTrue(json.contains("\n"), "Pretty-printed output should span multiple lines");
        assertDoesNotThrow(
                () -> objectMapper.readTree(json), "Pretty-printed output should be valid JSON");
    }

    @Test
    void testFlushAndCloseAreNoOps() throws Exception {
        EventLoggerConfig config = EventLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jEventLogger(config);
        logger.open(openParams);

        assertDoesNotThrow(() -> logger.flush(), "flush() should not throw");
        assertDoesNotThrow(() -> logger.close(), "close() should not throw");
    }

    private void append(Event event, ExecutionTraceContext executionTraceContext) throws Exception {
        logger.append(new EventContext(event), event, executionTraceContext);
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
