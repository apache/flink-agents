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

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.agents.OutputSchema;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for BaseChatModel class, Tests chat model functionality, prompt processing, and
 * response generation.
 */
class BaseChatModelTest {

    private TestChatModel chatModel;
    private Prompt simplePrompt;
    private Prompt conversationPrompt;

    /** Test implementation of BaseChatModel for testing purposes. */
    private static class TestChatModel extends BaseChatModelSetup {
        private String responsePrefix = "Test Response: ";

        public TestChatModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            // Simple test implementation that echoes the last user message

            String lastUserContent = "";
            for (ChatMessage message : messages) {
                if (message.getRole() == MessageRole.USER) {
                    lastUserContent = message.getText();
                }
            }

            if (lastUserContent.isEmpty()) {
                lastUserContent = "No user message found";
            }

            return new ChatMessage(MessageRole.ASSISTANT, responsePrefix + lastUserContent);
        }

        public void setResponsePrefix(String prefix) {
            this.responsePrefix = prefix;
        }
    }

    @BeforeEach
    void setUp() {
        chatModel =
                new TestChatModel(
                        new ResourceDescriptor(
                                TestChatModel.class.getName(), Collections.emptyMap()),
                        null);

        // Create simple prompt
        simplePrompt = Prompt.fromText("You are a helpful assistant. User says: {user_input}");

        // Create conversation prompt
        List<ChatMessage> conversationTemplate =
                Arrays.asList(
                        new ChatMessage(MessageRole.SYSTEM, "You are a helpful AI assistant."),
                        new ChatMessage(MessageRole.USER, "{user_message}"));
        conversationPrompt = Prompt.fromMessages(conversationTemplate);
    }

    @Test
    @DisplayName("Test ChatModel resource type")
    void testChatModelResourceType() {
        assertEquals(ResourceType.CHAT_MODEL, chatModel.getResourceType());
    }

    @Test
    @DisplayName("Test basic chat functionality")
    void testBasicChat() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Hello, how are you?");

        // Format the prompt with variables
        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getText().contains("Test Response:"));
    }

    @Test
    @DisplayName("Test chat with conversation prompt")
    void testChatWithConversationPrompt() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_message", "What's the weather like?");

        Prompt formattedPrompt =
                Prompt.fromMessages(
                        conversationPrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getText().contains("What's the weather like?"));
    }

    @Test
    @DisplayName("Test chat with empty prompt")
    void testChatWithEmptyPrompt() {
        Prompt emptyPrompt = Prompt.fromText("");

        ChatMessage response =
                chatModel.chat(emptyPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getText().contains("No user message found"));
    }

    @Test
    @DisplayName("Test chat with multiple user messages")
    void testChatWithMultipleUserMessages() {
        List<ChatMessage> multipleMessages =
                Arrays.asList(
                        new ChatMessage(MessageRole.SYSTEM, "You are a helpful assistant."),
                        new ChatMessage(MessageRole.USER, "First message"),
                        new ChatMessage(MessageRole.ASSISTANT, "I understand"),
                        new ChatMessage(
                                MessageRole.USER, "Second message - this should be the response"));

        Prompt multiPrompt = Prompt.fromMessages(multipleMessages);

        ChatMessage response =
                chatModel.chat(multiPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertTrue(response.getText().contains("Second message - this should be the response"));
    }

    @Test
    @DisplayName("Test chat model configuration")
    void testChatModelConfiguration() {
        chatModel.setResponsePrefix("Custom Response: ");

        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Test message");

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertTrue(response.getText().startsWith("Custom Response:"));
    }

    @Test
    @DisplayName("Test chat with system-only prompt")
    void testChatWithSystemOnlyPrompt() {
        Prompt systemOnlyPrompt =
                Prompt.fromMessages(
                        Arrays.asList(
                                new ChatMessage(MessageRole.SYSTEM, "System instruction only")));

        ChatMessage response =
                chatModel.chat(systemOnlyPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getText().contains("No user message found"));
    }

    @Test
    @DisplayName("Test chat response format")
    void testChatResponseFormat() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Format test");

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        // Verify response structure
        assertNotNull(response.getRole());
        assertNotNull(response.getText());
        assertNotNull(response.getToolCalls());
        assertNotNull(response.getExtraArgs());
        assertTrue(response.getText().length() > 0);
    }

    /** Connection that captures the messages passed to it for assertions. */
    private static class RecordingConnection extends BaseChatModelConnection {
        List<ChatMessage> capturedMessages;
        Map<String, Object> capturedModelParams;

        RecordingConnection() {
            super(
                    new ResourceDescriptor(
                            RecordingConnection.class.getName(), Collections.emptyMap()),
                    null);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            this.capturedMessages = new ArrayList<>(messages);
            this.capturedModelParams = new HashMap<>(modelParams);
            return new ChatMessage(MessageRole.ASSISTANT, "ok");
        }
    }

    /**
     * Connection that answers the native structured-output query with a configured value and
     * records what it was asked and what the schema-carrying chat was sent.
     */
    private static class StructuredRecordingConnection extends RecordingConnection {
        private final NativeStructuredOutputSupport support;
        int supportQueries;
        Object queriedSchema;
        List<Tool> queriedTools;
        Map<String, Object> queriedModelParams;
        List<Tool> capturedTools;
        Object capturedOutputSchema;

        StructuredRecordingConnection(NativeStructuredOutputSupport support) {
            this.support = support;
        }

        @Override
        protected NativeStructuredOutputSupport supportsNativeStructuredOutput(
                Object outputSchema, List<Tool> tools, Map<String, Object> modelParams) {
            supportQueries++;
            queriedSchema = outputSchema;
            queriedTools = tools;
            queriedModelParams = modelParams == null ? null : new HashMap<>(modelParams);
            return support;
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                List<Tool> tools,
                Map<String, Object> modelParams,
                Object outputSchema) {
            capturedTools = new ArrayList<>(tools);
            capturedOutputSchema = outputSchema;
            return chat(messages, tools, modelParams);
        }
    }

    /** Subclass that exposes setters so we can inject the connection and prompt directly. */
    private static class RecordingChatModelSetup extends BaseChatModelSetup {
        private final Map<String, Object> parameters;

        RecordingChatModelSetup(BaseChatModelConnection connection, Prompt prompt) {
            this(connection, prompt, null, Map.of());
        }

        RecordingChatModelSetup(
                BaseChatModelConnection connection,
                Prompt prompt,
                @Nullable StructuredOutputStrategy strategy,
                Map<String, Object> parameters) {
            super(
                    new ResourceDescriptor(
                            RecordingChatModelSetup.class.getName(),
                            strategy == null
                                    ? Collections.emptyMap()
                                    : Map.of("structured_output_strategy", strategy)),
                    null);
            this.connection = connection;
            this.prompt = prompt;
            this.parameters = parameters;
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>(parameters);
        }
    }

    private static final Map<String, Object> SETUP_PARAMS =
            Map.of("model", "setup-model", "temperature", 0.1);

    private static OutputSchema rowOutputSchema() {
        return new OutputSchema(
                new RowTypeInfo(
                        new TypeInformation[] {BasicTypeInfo.STRING_TYPE_INFO},
                        new String[] {"name"}));
    }

    static Stream<Arguments> strategyBySupport() {
        // A null expectation means the gate must reject the combination.
        return Stream.of(
                Arguments.of(
                        StructuredOutputStrategy.PROMPT,
                        NativeStructuredOutputSupport.INFEASIBLE,
                        false),
                Arguments.of(
                        StructuredOutputStrategy.PROMPT,
                        NativeStructuredOutputSupport.FEASIBLE,
                        false),
                Arguments.of(
                        StructuredOutputStrategy.PROMPT,
                        NativeStructuredOutputSupport.NATIVE_RECOMMENDED,
                        false),
                Arguments.of(
                        StructuredOutputStrategy.AUTO,
                        NativeStructuredOutputSupport.INFEASIBLE,
                        false),
                Arguments.of(
                        StructuredOutputStrategy.AUTO,
                        NativeStructuredOutputSupport.FEASIBLE,
                        false),
                Arguments.of(
                        StructuredOutputStrategy.AUTO,
                        NativeStructuredOutputSupport.NATIVE_RECOMMENDED,
                        true),
                Arguments.of(
                        StructuredOutputStrategy.NATIVE,
                        NativeStructuredOutputSupport.INFEASIBLE,
                        null),
                Arguments.of(
                        StructuredOutputStrategy.NATIVE,
                        NativeStructuredOutputSupport.FEASIBLE,
                        true),
                Arguments.of(
                        StructuredOutputStrategy.NATIVE,
                        NativeStructuredOutputSupport.NATIVE_RECOMMENDED,
                        true));
    }

    @ParameterizedTest(name = "{0} with {1} -> {2}")
    @MethodSource("strategyBySupport")
    @DisplayName(
            "Gate resolves the strategy against the connection's answer for a toolless request")
    void testWillApplyNativeStructuredOutputResolvesStrategy(
            StructuredOutputStrategy strategy,
            NativeStructuredOutputSupport support,
            Boolean expected) {
        StructuredRecordingConnection connection = new StructuredRecordingConnection(support);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(connection, null, strategy, SETUP_PARAMS);
        setup.getTools().add(new SubagentTool("helper", "help", "{\"type\":\"object\"}"));

        if (expected == null) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> setup.willApplyNativeStructuredOutput(String.class));
        } else {
            assertEquals(expected, setup.willApplyNativeStructuredOutput(String.class));
        }

        // The question concerns the request chatStructured would send: this schema, the setup's
        // own parameters, and no tools even when the setup binds some.
        assertEquals(1, connection.supportQueries);
        assertSame(String.class, connection.queriedSchema);
        assertTrue(connection.queriedTools.isEmpty());
        assertEquals(SETUP_PARAMS, connection.queriedModelParams);
    }

    @Test
    @DisplayName("Gate answers false for a null schema under every strategy without asking")
    void testWillApplyNativeStructuredOutputFalseForNullSchema() {
        for (StructuredOutputStrategy strategy : StructuredOutputStrategy.values()) {
            StructuredRecordingConnection connection =
                    new StructuredRecordingConnection(
                            NativeStructuredOutputSupport.NATIVE_RECOMMENDED);
            RecordingChatModelSetup setup =
                    new RecordingChatModelSetup(connection, null, strategy, SETUP_PARAMS);

            assertFalse(setup.willApplyNativeStructuredOutput(null), strategy.name());
            assertEquals(0, connection.supportQueries, strategy.name());
        }
    }

    static Stream<Arguments> schemaDescriptions() {
        OutputSchema rowSchema = rowOutputSchema();
        return Stream.of(
                Arguments.of(String.class, String.class.getName()),
                // The wrapper's toString names no schema, so the inner type is what names it.
                Arguments.of(rowSchema, rowSchema.getSchema().toString()));
    }

    @ParameterizedTest
    @MethodSource("schemaDescriptions")
    @DisplayName("NATIVE on an infeasible schema names the connection and the schema")
    void testWillApplyNativeStructuredOutputInfeasibleMessage(
            Object schema, String expectedDescription) {
        StructuredRecordingConnection connection =
                new StructuredRecordingConnection(NativeStructuredOutputSupport.INFEASIBLE);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        connection, null, StructuredOutputStrategy.NATIVE, SETUP_PARAMS);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> setup.willApplyNativeStructuredOutput(schema));

        assertTrue(
                e.getMessage().contains(StructuredRecordingConnection.class.getName()),
                e.getMessage());
        assertTrue(e.getMessage().contains(expectedDescription), e.getMessage());
    }

    @ParameterizedTest
    @EnumSource(
            value = StructuredOutputStrategy.class,
            names = {"AUTO", "PROMPT"})
    @DisplayName("Gate answers false for a schema when no connection is bound")
    void testWillApplyNativeStructuredOutputFalseWithoutConnection(
            StructuredOutputStrategy strategy) {
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(null, null, strategy, SETUP_PARAMS);

        assertFalse(setup.willApplyNativeStructuredOutput(String.class));
    }

    @Test
    @DisplayName("NATIVE without a bound connection fails naming the setup")
    void testWillApplyNativeStructuredOutputNativeWithoutConnection() {
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        null, null, StructuredOutputStrategy.NATIVE, SETUP_PARAMS);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> setup.willApplyNativeStructuredOutput(String.class));

        assertTrue(
                e.getMessage().contains(RecordingChatModelSetup.class.getName()), e.getMessage());
        assertTrue(e.getMessage().contains("no connection"), e.getMessage());
    }

    @ParameterizedTest
    @EnumSource(StructuredOutputStrategy.class)
    @DisplayName("Gate answers false for a null schema when no connection is bound")
    void testWillApplyNativeStructuredOutputNullSchemaWithoutConnection(
            StructuredOutputStrategy strategy) {
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(null, null, strategy, SETUP_PARAMS);

        assertFalse(setup.willApplyNativeStructuredOutput(null));
    }

    @Test
    @DisplayName("chatStructured() requires open()")
    void testChatStructuredRequiresOpen() {
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        null, null, StructuredOutputStrategy.AUTO, SETUP_PARAMS);

        NullPointerException e =
                assertThrows(
                        NullPointerException.class,
                        () ->
                                setup.chatStructured(
                                        List.of(new ChatMessage(MessageRole.USER, "hi")),
                                        Map.of(),
                                        String.class));
        assertTrue(e.getMessage().contains("open()"), e.getMessage());
    }

    @Test
    @DisplayName("chatStructured() sends the messages as given, no tools, and the schema")
    void testChatStructuredSendsMessagesAsGivenWithoutTools() {
        StructuredRecordingConnection connection =
                new StructuredRecordingConnection(NativeStructuredOutputSupport.NATIVE_RECOMMENDED);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        connection,
                        Prompt.fromText("Bound prompt"),
                        StructuredOutputStrategy.AUTO,
                        SETUP_PARAMS);
        setup.skillDiscoveryPrompt = "Available skills";
        setup.getTools().add(new SubagentTool("helper", "help", "{\"type\":\"object\"}"));
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, "already prepared"),
                        new ChatMessage(MessageRole.USER, "hi"));

        setup.chatStructured(messages, Map.of(), String.class);

        assertEquals(messages, connection.capturedMessages);
        assertTrue(connection.capturedTools.isEmpty());
        assertSame(String.class, connection.capturedOutputSchema);
    }

    @Test
    @DisplayName("chatStructured() removes tool traffic without modifying the caller's messages")
    void testChatStructuredRemovesToolTraffic() {
        StructuredRecordingConnection connection =
                new StructuredRecordingConnection(NativeStructuredOutputSupport.NATIVE_RECOMMENDED);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        connection, null, StructuredOutputStrategy.AUTO, SETUP_PARAMS);
        List<Map<String, Object>> toolCalls =
                List.of(Map.of("id", "call-1", "name", "lookup", "arguments", Map.of()));
        ChatMessage system = new ChatMessage(MessageRole.SYSTEM, "system");
        ChatMessage user = new ChatMessage(MessageRole.USER, "question");
        ChatMessage callWithText =
                new ChatMessage(
                        MessageRole.ASSISTANT,
                        "Let me look that up.",
                        toolCalls,
                        Map.of("refusal", "declined"));
        ChatMessage callWithoutText = new ChatMessage(MessageRole.ASSISTANT, "", toolCalls);
        ChatMessage toolResult =
                new ChatMessage(MessageRole.TOOL, "result", Map.of("externalId", "call-1"));
        ChatMessage answer = new ChatMessage(MessageRole.ASSISTANT, "The answer.");
        ChatMessage directive = new ChatMessage(MessageRole.USER, "Format it.");
        List<ChatMessage> history =
                new ArrayList<>(
                        List.of(
                                system,
                                user,
                                callWithText,
                                callWithoutText,
                                toolResult,
                                answer,
                                directive));
        List<ChatMessage> historyBefore = new ArrayList<>(history);

        setup.chatStructured(history, Map.of(), String.class);

        List<ChatMessage> sent = connection.capturedMessages;
        assertEquals(4, sent.size());
        assertSame(system, sent.get(0));
        assertSame(user, sent.get(1));
        assertSame(answer, sent.get(2));
        assertSame(directive, sent.get(3));
        // After the system message, user and assistant turns alternate.
        for (int i = 2; i < sent.size(); i++) {
            assertNotEquals(sent.get(i - 1).getRole(), sent.get(i).getRole());
        }

        assertEquals(historyBefore, history);
        assertEquals(toolCalls, callWithText.getToolCalls());
        assertEquals(toolCalls, callWithoutText.getToolCalls());
        assertEquals(Map.of("refusal", "declined"), callWithText.getExtraArgs());
        assertEquals(Map.of("externalId", "call-1"), toolResult.getExtraArgs());
    }

    @Test
    @DisplayName("chatStructured() drops tool traffic that no final answer follows")
    void testChatStructuredDropsTrailingToolTraffic() {
        StructuredRecordingConnection connection =
                new StructuredRecordingConnection(NativeStructuredOutputSupport.NATIVE_RECOMMENDED);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        connection, null, StructuredOutputStrategy.AUTO, SETUP_PARAMS);
        ChatMessage system = new ChatMessage(MessageRole.SYSTEM, "system");
        ChatMessage user = new ChatMessage(MessageRole.USER, "question");
        ChatMessage toolCall =
                new ChatMessage(
                        MessageRole.ASSISTANT,
                        "",
                        List.of(Map.of("id", "call-1", "name", "lookup", "arguments", Map.of())));
        ChatMessage toolResult = new ChatMessage(MessageRole.TOOL, "result");
        ChatMessage directive = new ChatMessage(MessageRole.USER, "Format it.");

        setup.chatStructured(
                List.of(system, user, toolCall, toolResult, directive), Map.of(), String.class);

        // Without a final answer the two user turns meet, which is why a caller appends one.
        assertEquals(List.of(system, user, directive), connection.capturedMessages);
    }

    @Test
    @DisplayName("chatStructured() merges per-call parameters over the setup's parameters")
    void testChatStructuredMergesModelParams() {
        StructuredRecordingConnection connection =
                new StructuredRecordingConnection(NativeStructuredOutputSupport.NATIVE_RECOMMENDED);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        connection, null, StructuredOutputStrategy.AUTO, SETUP_PARAMS);
        List<ChatMessage> messages = List.of(new ChatMessage(MessageRole.USER, "hi"));

        setup.chatStructured(messages, Map.of("temperature", 0.9), String.class);
        assertEquals(
                Map.of("model", "setup-model", "temperature", 0.9), connection.capturedModelParams);

        setup.chatStructured(messages, null, String.class);
        assertEquals(SETUP_PARAMS, connection.capturedModelParams);
    }

    @Test
    @DisplayName("chatStructured() refuses a null schema instead of sending an unconstrained call")
    void testChatStructuredRejectsNullSchema() {
        StructuredRecordingConnection connection =
                new StructuredRecordingConnection(NativeStructuredOutputSupport.NATIVE_RECOMMENDED);
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(
                        connection, null, StructuredOutputStrategy.AUTO, SETUP_PARAMS);

        assertThrows(
                NullPointerException.class,
                () ->
                        setup.chatStructured(
                                List.of(new ChatMessage(MessageRole.USER, "hi")), Map.of(), null));
        assertNull(connection.capturedMessages);
    }

    @Test
    @DisplayName("chat() fills prompt template from promptArgs parameter")
    void testChatFillsTemplateFromPromptArgsParameter() {
        RecordingConnection connection = new RecordingConnection();
        Prompt prompt = Prompt.fromText("Task: {key}");
        RecordingChatModelSetup setup = new RecordingChatModelSetup(connection, prompt);

        setup.chat(Collections.emptyList(), Map.of("key", "value"), Map.of());

        assertNotNull(connection.capturedMessages);
        assertEquals(1, connection.capturedMessages.size());
        assertEquals("Task: value", connection.capturedMessages.get(0).getText());
    }

    @Test
    @DisplayName("chat() does not read template vars from ChatMessage.extraArgs")
    void testChatDoesNotReadTemplateVarsFromExtraArgs() {
        RecordingConnection connection = new RecordingConnection();
        Prompt prompt = Prompt.fromText("Task: {key}");
        RecordingChatModelSetup setup = new RecordingChatModelSetup(connection, prompt);

        ChatMessage userMessage =
                new ChatMessage(MessageRole.USER, "hello", Map.of("key", "value"));
        setup.chat(List.of(userMessage), Map.of(), Map.of());

        assertNotNull(connection.capturedMessages);
        assertEquals(2, connection.capturedMessages.size());
        assertEquals("Task: {key}", connection.capturedMessages.get(0).getText());
        assertEquals("hello", connection.capturedMessages.get(1).getText());
    }

    @Test
    @DisplayName("chat() re-fills prompt template on subsequent invocations when args supplied")
    void testChatRefillsTemplateOnSubsequentInvocations() {
        RecordingConnection connection = new RecordingConnection();
        Prompt prompt = Prompt.fromText("Task: {key}");
        RecordingChatModelSetup setup = new RecordingChatModelSetup(connection, prompt);

        setup.chat(Collections.emptyList(), Map.of("key", "v1"), Map.of());
        assertNotNull(connection.capturedMessages);
        assertEquals(1, connection.capturedMessages.size());
        assertEquals("Task: v1", connection.capturedMessages.get(0).getText());

        ChatMessage toolResponse = new ChatMessage(MessageRole.TOOL, "tool result");
        setup.chat(List.of(toolResponse), Map.of("key", "v1"), Map.of());
        assertEquals(2, connection.capturedMessages.size());
        assertEquals("Task: v1", connection.capturedMessages.get(0).getText());
        assertEquals("tool result", connection.capturedMessages.get(1).getText());
    }

    @Test
    @DisplayName("Default chat() overload rejects an outputSchema it cannot translate")
    void testDefaultChatOverloadRejectsOutputSchema() {
        RecordingConnection connection = new RecordingConnection();

        // Dropping the schema instead would return an unconstrained response that the
        // caller has no way to tell apart from a schema-conforming one.
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        connection.chat(
                                List.of(new ChatMessage(MessageRole.USER, "hi")),
                                List.of(),
                                new HashMap<>(),
                                new Object()));

        // The rejection has to precede the delegation: a delegate-then-throw ordering
        // would still issue a real provider request before failing.
        assertNull(connection.capturedMessages);
    }

    @Test
    @DisplayName("Default chat() overload delegates to the 3-arg chat() for a null outputSchema")
    void testDefaultChatOverloadDelegatesForNullOutputSchema() {
        RecordingConnection connection = new RecordingConnection();
        Map<String, Object> modelParams = new HashMap<>();
        modelParams.put("temperature", 0.5);

        ChatMessage response =
                connection.chat(
                        List.of(new ChatMessage(MessageRole.USER, "hi")),
                        List.of(),
                        modelParams,
                        null);

        // The 3-arg chat() ran (it is what produces "ok") and the overload added nothing
        // to modelParams that could travel on to a provider SDK request.
        assertEquals("ok", response.getText());
        assertEquals(Map.of("temperature", 0.5), connection.capturedModelParams);
    }

    @Test
    @DisplayName("Default query reports every request infeasible")
    void testDefaultQueryIsInfeasible() {
        RecordingConnection connection = new RecordingConnection();
        Map<String, Object> modelParams = new HashMap<>();
        modelParams.put("model", "gpt-4o");

        // Both forms a schema arrives in: a POJO class, and a wrapper a connection would have
        // to unwrap before it could translate anything.
        assertEquals(
                NativeStructuredOutputSupport.INFEASIBLE,
                connection.supportsNativeStructuredOutput(String.class, List.of(), modelParams));
        assertEquals(
                NativeStructuredOutputSupport.INFEASIBLE,
                connection.supportsNativeStructuredOutput(
                        new OutputSchema(
                                new RowTypeInfo(
                                        new TypeInformation[] {BasicTypeInfo.STRING_TYPE_INFO},
                                        new String[] {"name"})),
                        List.of(),
                        modelParams));
    }

    @Test
    @DisplayName("Query accepts a null schema, tools and parameters without raising")
    void testDefaultQueryAcceptsNullInputs() {
        RecordingConnection connection = new RecordingConnection();

        // An unconstrained request is an ordinary input to ask about, not a misuse. A request
        // binding no tools may carry a null list, and a builder handed null parameters asks with
        // the same null it was handed.
        assertEquals(
                NativeStructuredOutputSupport.INFEASIBLE,
                connection.supportsNativeStructuredOutput(null, List.of(), Map.of()));
        assertEquals(
                NativeStructuredOutputSupport.INFEASIBLE,
                connection.supportsNativeStructuredOutput(String.class, null, null));
    }

    @Test
    @DisplayName("Query leaves the parameters a request would be built from intact")
    void testDefaultQueryDoesNotConsumeModelParams() {
        RecordingConnection connection = new RecordingConnection();
        Map<String, Object> modelParams = new HashMap<>();
        modelParams.put("model", "gpt-4o");
        modelParams.put("temperature", 0.5);

        connection.supportsNativeStructuredOutput(String.class, List.of(), modelParams);

        // The same map goes on to build the request the answer was about, so a query that
        // took a key out of it would answer about one request and build another.
        assertEquals(Map.of("model", "gpt-4o", "temperature", 0.5), modelParams);
    }

    @Test
    @DisplayName("Structured-output strategy defaults to AUTO when the descriptor omits it")
    void testStructuredOutputStrategyDefaultsToAuto() {
        RecordingChatModelSetup setup =
                new RecordingChatModelSetup(new RecordingConnection(), null);

        assertEquals(StructuredOutputStrategy.AUTO, setup.getStructuredOutputStrategy());
    }

    @Test
    @DisplayName("Structured-output strategy defaults to AUTO when the descriptor argument is null")
    void testStructuredOutputStrategyDefaultsToAutoForNullArgument() {
        // A descriptor argument present with a null value is indistinguishable from an
        // absent one here, so it resolves to the same default rather than failing.
        TestChatModel model =
                new TestChatModel(
                        new ResourceDescriptor(
                                TestChatModel.class.getName(),
                                Collections.singletonMap("structured_output_strategy", null)),
                        null);

        assertEquals(StructuredOutputStrategy.AUTO, model.getStructuredOutputStrategy());
    }

    @Test
    @DisplayName("Structured-output strategy is read from the descriptor argument")
    void testStructuredOutputStrategyReadFromDescriptor() {
        TestChatModel model =
                new TestChatModel(
                        new ResourceDescriptor(
                                TestChatModel.class.getName(),
                                Map.of("structured_output_strategy", "native")),
                        null);

        assertEquals(StructuredOutputStrategy.NATIVE, model.getStructuredOutputStrategy());
    }

    @Test
    @DisplayName("An unrecognized structured-output strategy is rejected instead of defaulting")
    void testUnknownStructuredOutputStrategyRejected() {
        ResourceDescriptor descriptor =
                new ResourceDescriptor(
                        TestChatModel.class.getName(),
                        Map.of("structured_output_strategy", "bogus"));

        assertThrows(IllegalArgumentException.class, () -> new TestChatModel(descriptor, null));
    }

    @Test
    @DisplayName("AUTO resolves to native only when native is recommended")
    void testAutoStrategyResolvesToNativeOnlyWhenRecommended() {
        assertTrue(
                StructuredOutputStrategy.AUTO.resolvesToNative(
                        NativeStructuredOutputSupport.NATIVE_RECOMMENDED));
        assertFalse(
                StructuredOutputStrategy.AUTO.resolvesToNative(
                        NativeStructuredOutputSupport.FEASIBLE));
        assertFalse(
                StructuredOutputStrategy.AUTO.resolvesToNative(
                        NativeStructuredOutputSupport.INFEASIBLE));
    }

    @Test
    @DisplayName("NATIVE resolves to native whenever the request can carry the schema")
    void testNativeStrategyResolvesToNativeWhenFeasible() {
        assertTrue(
                StructuredOutputStrategy.NATIVE.resolvesToNative(
                        NativeStructuredOutputSupport.NATIVE_RECOMMENDED));
        assertTrue(
                StructuredOutputStrategy.NATIVE.resolvesToNative(
                        NativeStructuredOutputSupport.FEASIBLE));
    }

    @Test
    @DisplayName("NATIVE on an infeasible request is rejected rather than degraded")
    void testNativeStrategyRejectsInfeasibleRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        StructuredOutputStrategy.NATIVE.resolvesToNative(
                                NativeStructuredOutputSupport.INFEASIBLE));
    }

    @Test
    @DisplayName("PROMPT never resolves to native")
    void testPromptStrategyNeverResolvesToNative() {
        for (NativeStructuredOutputSupport support : NativeStructuredOutputSupport.values()) {
            assertFalse(StructuredOutputStrategy.PROMPT.resolvesToNative(support));
        }
    }

    @Test
    @DisplayName("Test chat with long input")
    void testChatWithLongInput() {
        StringBuilder longInput = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longInput.append("This is a long message part ").append(i).append(". ");
        }

        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", longInput.toString());

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertTrue(response.getText().length() > 0);
    }
}
