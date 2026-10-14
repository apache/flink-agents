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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.logger.TraceLogLevel;
import org.apache.flink.agents.api.logger.TraceLogger;
import org.apache.flink.agents.api.logger.TraceLoggerConfig;
import org.apache.flink.agents.api.logger.TraceLoggerOpenParams;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.api.trace.TraceRecords;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobInfo;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class FileTraceLoggerTest {

    /** An inline base64 payload that must never survive into the Trace Log. */
    private static final String PAYLOAD = "aW5saW5lLXBheWxvYWQtYnl0ZXM=";

    /** A pre-signed URL whose credentials and query must never survive into the Trace Log. */
    private static final String SIGNED_URL =
            "https://user:secret@example.org/media/cat.png?X-Amz-Signature=abc123";

    /** The credential-free, query-free form the Trace Log is allowed to keep. */
    private static final String STRIPPED_URL = "https://example.org/media/cat.png";

    @TempDir Path tempDir;

    @Mock private StreamingRuntimeContext runtimeContext;

    @Mock private JobInfo jobInfo;

    @Mock private TaskInfo taskInfo;

    private FileTraceLogger logger;
    private TraceLoggerConfig config;
    private TraceLoggerOpenParams openParams;
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
        config = buildConfig(new HashMap<>());
        logger = new FileTraceLogger(config);
        openParams = new TraceLoggerOpenParams(runtimeContext);
    }

    /**
     * Builds a TraceLoggerConfig for the file logger, seeding the agent-config map with {@code
     * trace-log.base-dir} so tests can drop their per-test boilerplate and only specify the keys
     * they actually care about.
     */
    private TraceLoggerConfig buildConfig(Map<String, Object> extraAgentConfig) {
        Map<String, Object> agentConfig = new HashMap<>(extraAgentConfig);
        agentConfig.putIfAbsent(
                AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR.getKey(), tempDir.toString());
        return TraceLoggerConfig.builder()
                .loggerType(LoggerType.FILE)
                .property(TraceLoggerConfig.AGENT_CONFIG_PROPERTY_KEY, agentConfig)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (logger != null) {
            logger.close();
        }
    }

    @Test
    void testOffLevelDoesNotWriteOrCountTruncation() throws Exception {
        config = buildConfig(Map.of(AgentConfigOptions.TRACE_LOG_MAX_STRING_LENGTH.getKey(), 5));
        logger = new FileTraceLogger(config);
        logger.open(openParams);
        SimpleCounter truncatedRecords = new SimpleCounter();
        logger.setTruncatedRecordsCounter(truncatedRecords);

        append(
                logger,
                new InputEvent("long input that would be truncated"),
                null,
                TraceLogLevel.OFF);
        logger.flush();

        assertEquals("", Files.readString(getExpectedLogFilePath()));
        assertEquals(0, truncatedRecords.getCount());
    }

    @Test
    void testAppendMultipleEvents() throws Exception {
        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("input data");
        OutputEvent outputEvent = new OutputEvent("output data");
        TraceContext inputContext = null;
        TraceContext outputContext = null;

        append(logger, inputEvent, inputContext, TraceLogLevel.STANDARD);
        append(logger, outputEvent, outputContext, TraceLogLevel.STANDARD);
        logger.flush();

        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "Should have written two lines");

        // Verify the TraceRecord observing InputEvent.
        TraceRecord inputRecord = objectMapper.readValue(lines.get(0), TraceRecord.class);
        assertEquals(InputEvent.EVENT_TYPE, inputRecord.getContext().getEntityName());
        assertEquals("input data", inputRecord.getAttributes().get("input"));

        // Verify the TraceRecord observing OutputEvent.
        TraceRecord outputRecord = objectMapper.readValue(lines.get(1), TraceRecord.class);
        assertEquals(OutputEvent.EVENT_TYPE, outputRecord.getContext().getEntityName());
        assertEquals("output data", outputRecord.getAttributes().get("output"));
    }

    @Test
    void testAppendWithCustomEvent() throws Exception {
        // Given
        logger.open(openParams);
        TestCustomEvent customEvent = new TestCustomEvent("custom data", 42);
        UUID upstreamEventId = UUID.randomUUID();
        TraceContext actionContext =
                TraceContext.forAction(
                        TraceContext.forInputRun("order-1", "agent"),
                        "custom_action",
                        upstreamEventId.toString());
        TraceRecord record =
                TraceRecord.create(
                        TraceContext.forEvent(
                                actionContext,
                                customEvent.getType(),
                                customEvent.getId().toString(),
                                upstreamEventId.toString(),
                                "custom_action"),
                        null,
                        null,
                        customEvent.getAttributes());

        // When
        logger.append(record, TraceLogLevel.STANDARD);
        logger.flush();

        // Then
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());

        // Verify JSON structure
        JsonNode jsonNode = objectMapper.readTree(lines.get(0));
        assertEquals(TestCustomEvent.EVENT_TYPE, jsonNode.get("entityName").asText());

        JsonNode attrsNode = jsonNode.get("attributes");
        assertEquals("custom data", attrsNode.get("customData").asText());
        assertEquals(42, attrsNode.get("customNumber").asInt());

        // Verify the native record
        TraceRecord deserializedRecord = objectMapper.readValue(lines.get(0), TraceRecord.class);
        assertNotNull(deserializedRecord);
        assertEquals(TestCustomEvent.EVENT_TYPE, deserializedRecord.getContext().getEntityName());

        assertEquals("custom data", deserializedRecord.getAttributes().get("customData"));
        assertEquals(42, deserializedRecord.getAttributes().get("customNumber"));
        assertEquals(
                customEvent.getId().toString(),
                deserializedRecord.getContext().getEntityMetadata().get("eventId"));
        assertEquals(
                upstreamEventId.toString(),
                deserializedRecord.getContext().getEntityMetadata().get("upstreamEventId"));
        assertEquals(
                "custom_action",
                deserializedRecord.getContext().getEntityMetadata().get("upstreamActionName"));
        assertEquals(
                actionContext.getExecutionId(),
                deserializedRecord.getContext().getEntityMetadata().get("producerExecutionId"));
        assertNull(deserializedRecord.getContext().getExecutionId());
        assertNull(deserializedRecord.getStatus());
    }

    @Test
    void testAppendInAppendMode() throws Exception {
        // Given - first session
        logger.open(openParams);
        InputEvent event1 = new InputEvent("first event");
        TraceContext context1 = null;
        append(logger, event1, context1, TraceLogLevel.STANDARD);
        logger.close();

        // When - second session (append mode)
        FileTraceLogger secondLogger = new FileTraceLogger(config);
        secondLogger.open(openParams);
        InputEvent event2 = new InputEvent("second event");
        TraceContext context2 = null;
        append(secondLogger, event2, context2, TraceLogLevel.STANDARD);
        secondLogger.flush();
        secondLogger.close();

        // Then
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "Should have both events in append mode");

        // Verify JSON structure
        JsonNode firstEventJson = objectMapper.readTree(lines.get(0));
        assertEquals("first event", firstEventJson.get("attributes").get("input").asText());

        JsonNode secondEventJson = objectMapper.readTree(lines.get(1));
        assertEquals("second event", secondEventJson.get("attributes").get("input").asText());

        // Verify the native record
        TraceRecord firstRecord = objectMapper.readValue(lines.get(0), TraceRecord.class);
        assertEquals(InputEvent.EVENT_TYPE, firstRecord.getContext().getEntityName());
        assertEquals("first event", firstRecord.getAttributes().get("input"));

        TraceRecord secondRecord = objectMapper.readValue(lines.get(1), TraceRecord.class);
        assertEquals(InputEvent.EVENT_TYPE, secondRecord.getContext().getEntityName());
        assertEquals("second event", secondRecord.getAttributes().get("input"));
    }

    @Test
    void testMultipleSubTasks() throws Exception {
        // Given - subtask 0
        logger.open(openParams);
        InputEvent event1 = new InputEvent("subtask 0 event");
        TraceContext context1 = null;
        append(logger, event1, context1, TraceLogLevel.STANDARD);
        logger.flush();

        // Given - subtask 1
        when(taskInfo.getIndexOfThisSubtask()).thenReturn(1);
        FileTraceLogger logger2 = new FileTraceLogger(config);
        TraceLoggerOpenParams openParams2 = new TraceLoggerOpenParams(runtimeContext);
        logger2.open(openParams2);
        InputEvent event2 = new InputEvent("subtask 1 event");
        TraceContext context2 = null;
        append(logger2, event2, context2, TraceLogLevel.STANDARD);
        logger2.flush();
        logger2.close();

        // Then - verify separate files with structured names
        Path subtask0File =
                tempDir.resolve(
                        String.format(
                                "traces-%s-%s-%d.log", testJobId.toString(), testTaskName, 0));
        Path subtask1File =
                tempDir.resolve(
                        String.format(
                                "traces-%s-%s-%d.log", testJobId.toString(), testTaskName, 1));

        assertTrue(Files.exists(subtask0File), "Subtask 0 file should exist");
        assertTrue(Files.exists(subtask1File), "Subtask 1 file should exist");

        List<String> subtask0Lines = Files.readAllLines(subtask0File);
        List<String> subtask1Lines = Files.readAllLines(subtask1File);

        assertEquals(1, subtask0Lines.size());
        assertEquals(1, subtask1Lines.size());

        // Verify JSON structure
        JsonNode subtask0EventJson = objectMapper.readTree(subtask0Lines.get(0));
        JsonNode subtask1EventJson = objectMapper.readTree(subtask1Lines.get(0));

        assertEquals("subtask 0 event", subtask0EventJson.get("attributes").get("input").asText());
        assertEquals("subtask 1 event", subtask1EventJson.get("attributes").get("input").asText());

        // Verify the native record
        TraceRecord subtask0Record =
                objectMapper.readValue(subtask0Lines.get(0), TraceRecord.class);
        assertEquals(InputEvent.EVENT_TYPE, subtask0Record.getContext().getEntityName());
        assertEquals("subtask 0 event", subtask0Record.getAttributes().get("input"));

        TraceRecord subtask1Record =
                objectMapper.readValue(subtask1Lines.get(0), TraceRecord.class);
        assertEquals(InputEvent.EVENT_TYPE, subtask1Record.getContext().getEntityName());
        assertEquals("subtask 1 event", subtask1Record.getAttributes().get("input"));
    }

    @Test
    void testPrettyPrintOutputsFormattedJson() throws Exception {
        // Given - config with prettyPrint enabled
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT.getKey(), true);
        config = buildConfig(agentConfig);
        logger = new FileTraceLogger(config);

        logger.open(openParams);
        InputEvent inputEvent = new InputEvent("test input");
        append(logger, inputEvent, null, TraceLogLevel.STANDARD);
        logger.flush();

        // Then - output should be valid JSON spanning multiple lines (pretty-printed)
        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        // Pretty-printed TraceRecord JSON spans multiple lines.
        assertTrue(lines.size() > 1, "Pretty-printed JSON should span multiple lines");
        // Each line after the first should be indented
        assertTrue(
                lines.subList(1, lines.size()).stream().anyMatch(line -> line.startsWith("  ")),
                "Pretty-printed JSON lines should be indented");
        // The entire content should still be valid JSON
        String content = String.join("\n", lines);
        assertDoesNotThrow(
                () -> objectMapper.readValue(content, TraceRecord.class),
                "Pretty-printed output should be valid JSON deserializable to TraceRecord");
    }

    @Test
    void testStandardLevelTruncation() throws Exception {
        // Given - STANDARD level and a small max-string-length for easy testing
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("trace-log.standard.max-string-length", 10);
        agentConfig.put("trace-log.standard.max-array-elements", 20);
        agentConfig.put("trace-log.standard.max-depth", 5);

        config = buildConfig(agentConfig);
        logger = new FileTraceLogger(config);
        logger.open(openParams);

        // Use a custom event with a very long string field
        TestCustomEvent event =
                new TestCustomEvent("this is a very long string that exceeds 10", 1);
        TraceContext context = null;

        append(logger, event, context, TraceLogLevel.STANDARD);
        logger.flush();

        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());

        JsonNode jsonNode = objectMapper.readTree(lines.get(0));
        assertEquals(1, jsonNode.get("schemaVersion").intValue());
        assertEquals("STANDARD", jsonNode.get("level").asText());
        assertEquals(
                event.getId().toString(),
                jsonNode.get("entityMetadata").get("eventId").textValue(),
                "Event identity metadata should not be truncated");

        // The customData field (inside attributes) should be truncated
        JsonNode attrsNode = jsonNode.get("attributes");
        JsonNode customDataNode = attrsNode.get("customData");
        assertTrue(
                customDataNode.has("truncatedString"),
                "Long string should be truncated at STANDARD level");
        assertTrue(customDataNode.has("omittedChars"));
    }

    @Test
    void testVerboseLevelNoTruncation() throws Exception {
        // Given - a small limit that VERBOSE level bypasses
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("trace-log.standard.max-string-length", 10);

        config = buildConfig(agentConfig);
        logger = new FileTraceLogger(config);
        logger.open(openParams);

        TestCustomEvent event =
                new TestCustomEvent("this is a very long string that exceeds 10", 1);
        TraceContext context = null;

        append(logger, event, context, TraceLogLevel.VERBOSE);
        logger.flush();

        Path logFile = getExpectedLogFilePath();
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());

        JsonNode jsonNode = objectMapper.readTree(lines.get(0));
        assertEquals(1, jsonNode.get("schemaVersion").intValue());
        assertEquals("VERBOSE", jsonNode.get("level").asText());

        // The customData field (inside attributes) should NOT be truncated
        JsonNode attrsNode = jsonNode.get("attributes");
        assertTrue(
                attrsNode.get("customData").isTextual(),
                "String should be preserved at VERBOSE level");
        assertEquals(
                "this is a very long string that exceeds 10", attrsNode.get("customData").asText());
    }

    @Test
    void testMediaPayloadsNeverReachTheLogAtStandard() throws Exception {
        assertMediaPayloadsSanitizedAt(TraceLogLevel.STANDARD);
    }

    @Test
    void testMediaPayloadsNeverReachTheLogAtVerbose() throws Exception {
        // VERBOSE lifts truncation, not sanitization: payload bytes and raw URLs stay out.
        assertMediaPayloadsSanitizedAt(TraceLogLevel.VERBOSE);
    }

    private void assertMediaPayloadsSanitizedAt(TraceLogLevel level) throws Exception {
        logger.open(openParams);

        String payload = PAYLOAD;
        String signedUrl = SIGNED_URL;
        ChatRequestEvent event = multimodalChatRequest();
        ChatMessage message = event.getMessages().get(0);

        append(logger, event, null, level);
        append(
                logger,
                ChatResponseEvent.success(
                        UUID.randomUUID(),
                        new ChatResult(
                                ChatMessage.assistant(message.getBlocks()),
                                "local",
                                "response-id",
                                null,
                                "stop",
                                Map.of("provider", "ollama"))),
                null,
                level);
        ChatMessage assistant =
                ChatMessage.assistant(
                                List.of(
                                        new ReasoningBlock(
                                                "check the weather", Map.of("signature", "sig")),
                                        new ToolCallBlock(
                                                "call-1",
                                                "weather",
                                                Map.of("city", "Berlin"),
                                                Map.of("provider", "ollama"))))
                        .withMetadata(Map.of("turn", 1));
        ChatMessage tool =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call-1",
                                List.of(
                                        new TextBlock("sunny"),
                                        ImageBlock.fromBase64("image/png", payload),
                                        new DocumentBlock(
                                                "application/pdf",
                                                new UrlSource(signedUrl),
                                                "cat.pdf",
                                                42L,
                                                null)),
                                false,
                                Map.of("source", "weather")));
        append(logger, new ChatRequestEvent("test-model", List.of(assistant, tool)), null, level);
        ToolResponse toolResponse =
                ToolResponse.success(
                                ((ToolResultBlock) tool.getBlocks().get(0)).getBlocks(),
                                12L,
                                "weather")
                        .withMetadata(Map.of("source", "weather"));
        append(
                logger,
                new ToolResponseEvent(
                        UUID.randomUUID(),
                        Map.of("call-1", toolResponse),
                        Map.of("call-1", true),
                        Map.of()),
                null,
                level);
        logger.flush();

        assertSanitizedMediaLogLine();
        Path logFile = getExpectedLogFilePath();
        JsonNode logged =
                objectMapper
                        .readTree(Files.readAllLines(logFile).get(0))
                        .at("/attributes/messages/0");

        String responseLine = Files.readAllLines(logFile).get(1);
        assertFalse(responseLine.contains(payload), "Response inline payload must never be logged");
        assertFalse(
                responseLine.contains("secret"), "Response URL credentials must never be logged");
        assertFalse(
                responseLine.contains("X-Amz-Signature"),
                "Response URL query must never be logged");
        JsonNode response = objectMapper.readTree(responseLine).get("attributes").get("response");
        assertEquals("assistant", response.at("/message/role").asText());
        assertEquals("ollama", response.at("/metadata/provider").asText());
        assertEquals(logged.get("blocks"), response.at("/message/blocks"));
        assertEquals("response-id", response.get("response_id").asText());
        String toolLine = Files.readAllLines(logFile).get(2);
        JsonNode messages = objectMapper.readTree(toolLine).at("/attributes/messages");
        assertEquals(1, messages.at("/0/metadata/turn").asInt());
        assertEquals("check the weather", messages.at("/0/blocks/0/text").asText());
        assertEquals("sig", messages.at("/0/blocks/0/metadata/signature").asText());
        assertEquals("Berlin", messages.at("/0/blocks/1/input/city").asText());
        assertEquals("ollama", messages.at("/0/blocks/1/metadata/provider").asText());
        assertEquals("call-1", messages.at("/1/blocks/0/call_id").asText());
        assertEquals("sunny", messages.at("/1/blocks/0/blocks/0/text").asText());
        assertEquals("weather", messages.at("/1/blocks/0/metadata/source").asText());
        assertFalse(toolLine.contains(payload));
        assertFalse(toolLine.contains("secret"));
        assertFalse(toolLine.contains("X-Amz-Signature"));
        String toolResponseLine = Files.readAllLines(logFile).get(3);
        assertFalse(toolResponseLine.contains(payload));
        assertFalse(toolResponseLine.contains("secret"));
        assertFalse(toolResponseLine.contains("X-Amz-Signature"));
        JsonNode loggedToolResponse =
                objectMapper.readTree(toolResponseLine).at("/attributes/responses/call-1");
        assertEquals(messages.at("/1/blocks/0/blocks"), loggedToolResponse.get("blocks"));
        assertTrue(loggedToolResponse.get("success").asBoolean());
        assertEquals(12L, loggedToolResponse.get("execution_time_ms").asLong());
        assertEquals("weather", loggedToolResponse.get("tool_name").asText());
        assertEquals("weather", loggedToolResponse.at("/metadata/source").asText());

        // The normal wire mapper must retain the payload, even after logging the same object.
        String wire = objectMapper.writeValueAsString(toolResponse);
        assertTrue(wire.contains(payload));
        assertTrue(wire.contains(signedUrl));
        assertEquals(toolResponse, objectMapper.readValue(wire, ToolResponse.class));
    }

    @Test
    void testCrossLanguageMediaPayloadsNeverReachTheLogAtStandard() throws Exception {
        assertCrossLanguageMediaSanitizedAt(TraceLogLevel.STANDARD);
    }

    @Test
    void testCrossLanguageMediaPayloadsNeverReachTheLogAtVerbose() throws Exception {
        // VERBOSE lifts truncation, not sanitization, on the cross-language path either.
        assertCrossLanguageMediaSanitizedAt(TraceLogLevel.VERBOSE);
    }

    /**
     * Runtime-layer unit test for the cross-language Trace Log path: a multimodal chat request that
     * originates in Python arrives as generic wire JSON, and its inline payload and signed URL must
     * still be sanitized before they reach the log.
     *
     * <p>{@code Event.fromJson} restores the concrete {@link ChatRequestEvent}, so its messages
     * become typed {@link ChatMessage} instances rather than generic maps. Their typed blocks
     * engage the Trace Log's content-block serializer; if the blocks remained generic maps, their
     * payload and URL credentials would be logged verbatim.
     *
     * <p>This drives the deserialization seam directly, so it is a focused unit test rather than a
     * public-API end-to-end test: {@code Event.fromJson} is an internal bridge entry point, not a
     * documented user-facing API. The end-to-end counterpart, which emits the multimodal request
     * from a real Python agent through {@code ctx.send_event} and reads the resulting Trace Log on
     * a MiniCluster, lives in the Python suite at {@code
     * e2e_tests/e2e_tests_integration/trace_log_media_sanitization_test.py}.
     */
    private void assertCrossLanguageMediaSanitizedAt(TraceLogLevel level) throws Exception {
        logger.open(openParams);

        // The wire format is not sanitized: it carries the payload and the signed URL verbatim,
        // exactly as ChatMessage.model_dump_json() emits them on the Python side.
        String wireJson = objectMapper.writeValueAsString(multimodalChatRequest());
        assertTrue(wireJson.contains(PAYLOAD), "the wire format must carry the inline payload");
        assertTrue(
                wireJson.contains("X-Amz-Signature"),
                "the wire format must carry the signed URL query");

        Event restored = Event.fromJson(wireJson);
        assertTrue(
                restored instanceof ChatRequestEvent,
                "a cross-language chat request must restore to its concrete type");
        assertTrue(
                ((ChatRequestEvent) restored).getMessages().get(0) instanceof ChatMessage,
                "restored messages must be typed ChatMessage instances, not generic maps");

        append(logger, restored, null, level);
        logger.flush();

        assertSanitizedMediaLogLine();
    }

    /** A user message mixing text, an inline base64 image, and a signed-URL document. */
    private static ChatRequestEvent multimodalChatRequest() {
        ChatMessage message =
                ChatMessage.user(
                        List.of(
                                TextBlock.of("what is in this picture?"),
                                ImageBlock.fromBase64("image/png", PAYLOAD),
                                new DocumentBlock(
                                        "application/pdf",
                                        new UrlSource(SIGNED_URL),
                                        "cat.pdf",
                                        42L,
                                        null)));
        return new ChatRequestEvent("test-model", List.of(message));
    }

    /** Asserts the single logged line kept media metadata but dropped every payload/credential. */
    private void assertSanitizedMediaLogLine() throws Exception {
        Path logFile = getExpectedLogFilePath();
        String line = Files.readAllLines(logFile).get(0);
        assertFalse(line.contains(PAYLOAD), "Inline payload bytes must never be logged");
        assertFalse(line.contains("secret"), "URL credentials must never be logged");
        assertFalse(line.contains("X-Amz-Signature"), "URL query strings must never be logged");

        JsonNode logged = objectMapper.readTree(line).get("attributes").get("messages").get(0);
        assertEquals("what is in this picture?", logged.get("blocks").get(0).get("text").asText());
        JsonNode image = logged.get("blocks").get(1);
        assertEquals("image/png", image.get("media_type").asText());
        assertEquals("base64", image.get("source").get("type").asText());
        assertFalse(image.get("source").has("data"), "Inline data is dropped, not masked");
        assertTrue(image.get("size_bytes").isNumber(), "Derived size metadata should be logged");
        JsonNode document = logged.get("blocks").get(2);
        assertEquals(STRIPPED_URL, document.get("source").get("url").asText());
        assertEquals("cat.pdf", document.get("name").asText());
        assertEquals(42L, document.get("size_bytes").asLong());
    }

    @Test
    void testExecutionTruncationPreservesContextAndProblemCategory() throws Exception {
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("trace-log.standard.max-string-length", 5);
        config = buildConfig(agentConfig);
        logger = new FileTraceLogger(config);
        logger.open(openParams);
        SimpleCounter truncatedRecords = new SimpleCounter();
        logger.setTruncatedRecordsCounter(truncatedRecords);
        TraceContext actionContext =
                TraceContext.forAction(
                        TraceContext.forInputRun("long-business-key", "long-agent-name"),
                        "long-action-name",
                        "long-trigger-event-id");
        TraceRecord record =
                TraceRecords.failed(
                        actionContext,
                        "LongErrorType",
                        "long error message",
                        "long-problem-category");

        logger.append(record, TraceLogLevel.STANDARD);
        logger.flush();

        JsonNode json = objectMapper.readTree(Files.readAllLines(getExpectedLogFilePath()).get(0));
        assertEquals(1, json.get("schemaVersion").intValue());
        assertEquals(actionContext.getInputRunId(), json.get("inputRunId").asText());
        assertEquals(actionContext.getExecutionId(), json.get("executionId").asText());
        assertEquals("long-business-key", json.get("businessKey").asText());
        assertEquals("long-agent-name", json.get("agentName").asText());
        assertEquals("long-action-name", json.get("entityName").asText());
        assertEquals(
                "long-trigger-event-id", json.get("entityMetadata").get("triggerEventId").asText());
        assertEquals("failed", json.get("status").asText());
        assertEquals("long-problem-category", json.get("problemCategory").asText());
        assertTrue(json.get("attributes").get("errorType").has("truncatedString"));
        assertTrue(json.get("attributes").get("errorMessage").has("truncatedString"));
        assertEquals(1, truncatedRecords.getCount());
        assertEquals("long error message", record.getAttributes().get("errorMessage"));
        assertFalse(json.has("eventType"));
        assertFalse(json.has("eventId"));
    }

    private Path getExpectedLogFilePath() {
        return tempDir.resolve(
                String.format(
                        "traces-%s-%s-%d.log", testJobId.toString(), testTaskName, testSubTaskId));
    }

    private static void append(
            TraceLogger logger, Event event, TraceContext sourceContext, TraceLogLevel level)
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
                level);
    }

    /** Custom test event class using the attributes-based pattern. */
    public static class TestCustomEvent extends Event {
        public static final String EVENT_TYPE = "TestCustomEvent";

        public TestCustomEvent(String customData, int customNumber) {
            super(EVENT_TYPE);
            setAttr("customData", customData);
            setAttr("customNumber", customNumber);
        }
    }
}
