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
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.logger.TraceLogDetail;
import org.apache.flink.agents.api.logger.TraceLoggerConfig;
import org.apache.flink.agents.api.logger.TraceLoggerOpenParams;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.api.trace.TraceRecords;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobInfo;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.metrics.SimpleCounter;
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

class Slf4jTraceLoggerTest {

    @Mock private StreamingRuntimeContext runtimeContext;

    @Mock private JobInfo jobInfo;

    @Mock private TaskInfo taskInfo;

    private Slf4jTraceLogger logger;
    private TraceLoggerOpenParams openParams;
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

        openParams = new TraceLoggerOpenParams(runtimeContext);

        // Set up log4j2 test appender to capture trace log output
        testAppender = new TestAppender("TestSlf4jAppender");
        testAppender.start();

        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration config = loggerContext.getConfiguration();
        config.addAppender(testAppender);

        LoggerConfig loggerConfig = config.getLoggerConfig(Slf4jTraceLogger.TRACE_LOGGER_NAME);
        if (!loggerConfig.getName().equals(Slf4jTraceLogger.TRACE_LOGGER_NAME)) {
            loggerConfig =
                    new LoggerConfig(
                            Slf4jTraceLogger.TRACE_LOGGER_NAME,
                            org.apache.logging.log4j.Level.INFO,
                            false);
            config.addLogger(Slf4jTraceLogger.TRACE_LOGGER_NAME, loggerConfig);
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
            LoggerConfig loggerConfig = config.getLoggerConfig(Slf4jTraceLogger.TRACE_LOGGER_NAME);
            loggerConfig.removeAppender(testAppender.getName());
            loggerContext.updateLoggers();
        }
    }

    @Test
    void testOffDetailDoesNotWriteOrCountTruncation() throws Exception {
        TraceLoggerConfig config =
                TraceLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(
                                TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY,
                                Map.of(AgentConfigOptions.TRACE_LOG_MAX_STRING_LENGTH.getKey(), 5))
                        .build();
        logger = new Slf4jTraceLogger(config);
        logger.open(openParams);
        SimpleCounter truncatedRecords = new SimpleCounter();
        logger.setTruncatedRecordsCounter(truncatedRecords);

        append(new InputEvent("long input that would be truncated"), null, TraceLogDetail.OFF);
        logger.flush();

        assertTrue(testAppender.getMessages().isEmpty());
        assertEquals(0, truncatedRecords.getCount());
    }

    @Test
    void testAppendWritesJsonWithSubtaskContext() throws Exception {
        TraceLoggerConfig config = TraceLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jTraceLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        TraceContext context = null;

        append(inputEvent, context, TraceLogDetail.STANDARD);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size(), "Should have logged one message");

        assertFalse(messages.get(0).contains("\n"), "Default output should be single line");
        logger.flush();
        logger.close();
        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        assertEquals("STANDARD", jsonNode.get("detail").asText());
        // Verify subtask context fields
        assertEquals(testJobId.toString(), jsonNode.get("jobId").asText());
        assertEquals(testTaskName, jsonNode.get("taskName").asText());
        assertEquals(testSubTaskId, jsonNode.get("subtaskId").asInt());
        // Verify event content
        assertNotNull(jsonNode.get("timestamp"));
        assertNotNull(jsonNode.get("entityMetadata").get("eventId"));
        assertNotNull(jsonNode.get("attributes"));
        assertEquals(InputEvent.EVENT_TYPE, jsonNode.get("entityName").asText());
        assertEquals("event", jsonNode.get("entityType").asText());
        assertFalse(jsonNode.has("eventId"));
        assertFalse(jsonNode.has("eventType"));
        assertFalse(jsonNode.has("eventAttributes"));
        assertFalse(jsonNode.has("executionId"));
        assertFalse(jsonNode.has("status"));
    }

    @Test
    void testAppendMultipleEvents() throws Exception {
        TraceLoggerConfig config = TraceLoggerConfig.builder().loggerType(LoggerType.SLF4J).build();
        logger = new Slf4jTraceLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");

        append(inputEvent, null, TraceLogDetail.STANDARD);
        append(outputEvent, null, TraceLogDetail.STANDARD);

        List<String> messages = testAppender.getMessages();
        assertEquals(2, messages.size(), "Should have logged two messages");

        JsonNode inputJson = objectMapper.readTree(messages.get(0));
        assertEquals("input data", inputJson.get("attributes").get("input").asText());

        JsonNode outputJson = objectMapper.readTree(messages.get(1));
        assertEquals("output data", outputJson.get("attributes").get("output").asText());
    }

    @Test
    void testStandardDetailTruncatesOnlyEventAttributes() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put(AgentConfigOptions.TRACE_LOG_MAX_STRING_LENGTH.getKey(), 10);
        TraceLoggerConfig config =
                TraceLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                        .build();
        logger = new Slf4jTraceLogger(config);
        logger.open(openParams);

        InputEvent inputEvent =
                new InputEvent("this is a very long string that exceeds ten characters");
        append(inputEvent, null, TraceLogDetail.STANDARD);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());
        JsonNode jsonNode = objectMapper.readTree(messages.get(0));
        assertEquals(
                inputEvent.getId().toString(),
                jsonNode.get("entityMetadata").get("eventId").asText(),
                "Event identity metadata should not be truncated");
        assertTrue(
                jsonNode.get("attributes").get("input").has("truncatedString"),
                "Long Event payload strings should be truncated");
    }

    @Test
    void testExecutionTruncationPreservesRelationshipsAndProblemCategory() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put(AgentConfigOptions.TRACE_LOG_MAX_STRING_LENGTH.getKey(), 5);
        TraceLoggerConfig config =
                TraceLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                        .build();
        logger = new Slf4jTraceLogger(config);
        logger.open(openParams);
        SimpleCounter truncatedRecords = new SimpleCounter();
        logger.setTruncatedRecordsCounter(truncatedRecords);
        TraceContext actionContext =
                TraceContext.forAction(
                        TraceContext.forInputRun("business-key", "agent-name"),
                        "action-name",
                        "input-id");
        TraceContext toolContext =
                actionContext.childExecution("tool", "tool-name", Map.of("detail", "long-detail"));
        TraceRecord record =
                TraceRecords.failed(
                        toolContext,
                        "LongErrorType",
                        "long error message",
                        "long-problem-category");

        logger.append(record, TraceLogDetail.STANDARD);

        JsonNode json = objectMapper.readTree(testAppender.getMessages().get(0));
        assertEquals(toolContext.getInputRunId(), json.get("inputRunId").asText());
        assertEquals(toolContext.getExecutionId(), json.get("executionId").asText());
        assertEquals(actionContext.getExecutionId(), json.get("parentExecutionId").asText());
        assertEquals("business-key", json.get("businessKey").asText());
        assertEquals("tool-name", json.get("entityName").asText());
        assertEquals("long-detail", json.get("entityMetadata").get("detail").asText());
        assertEquals("failed", json.get("status").asText());
        assertEquals("long-problem-category", json.get("problemCategory").asText());
        assertTrue(json.get("attributes").get("errorMessage").has("truncatedString"));
        assertEquals(1, truncatedRecords.getCount());
        assertEquals("long error message", record.getAttributes().get("errorMessage"));
        assertFalse(json.has("eventType"));
        assertFalse(json.has("eventId"));
    }

    @Test
    void testEnablePrettyPrint() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT.getKey(), true);
        TraceLoggerConfig config =
                TraceLoggerConfig.builder()
                        .loggerType(LoggerType.SLF4J)
                        .property(TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                        .build();
        logger = new Slf4jTraceLogger(config);
        logger.open(openParams);

        InputEvent inputEvent = new InputEvent("test input");
        append(inputEvent, null, TraceLogDetail.STANDARD);

        List<String> messages = testAppender.getMessages();
        assertEquals(1, messages.size());

        String json = messages.get(0);
        assertTrue(json.contains("\n"), "Pretty-printed output should span multiple lines");
        assertDoesNotThrow(
                () -> objectMapper.readTree(json), "Pretty-printed output should be valid JSON");
    }

    private void append(Event event, TraceContext sourceContext, TraceLogDetail detail)
            throws Exception {
        logger.append(
                TraceRecord.create(
                        TraceContext.forEvent(
                                sourceContext,
                                event.getType(),
                                event.getId().toString(),
                                null,
                                null),
                        null,
                        null,
                        event.getAttributes()),
                detail);
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
