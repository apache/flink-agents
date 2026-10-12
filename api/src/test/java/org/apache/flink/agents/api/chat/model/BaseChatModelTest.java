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
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        public ChatResult chat(
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

            return new ChatResult(
                    ChatMessage.assistant(
                            List.of(new TextBlock(responsePrefix + lastUserContent))));
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

        ChatResult response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertFalse(response.getMessage().getBlocks().isEmpty());
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

        ChatResult response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertFalse(response.getMessage().getBlocks().isEmpty());
        assertTrue(response.getText().contains("What's the weather like?"));
    }

    @Test
    @DisplayName("Test chat with empty prompt")
    void testChatWithEmptyPrompt() {
        Prompt emptyPrompt = Prompt.fromText("");

        ChatResult response =
                chatModel.chat(emptyPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertFalse(response.getMessage().getBlocks().isEmpty());
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

        ChatResult response =
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

        ChatResult response =
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

        ChatResult response =
                chatModel.chat(systemOnlyPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertFalse(response.getMessage().getBlocks().isEmpty());
        assertTrue(response.getText().contains("No user message found"));
    }

    @Test
    @DisplayName("Test chat response format")
    void testChatResponseFormat() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Format test");

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatResult response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        // Verify response structure
        assertNotNull(response.getMessage().getBlocks());
        assertNotNull(response.getText());
        assertNotNull(response.getToolCalls());
        assertNotNull(response.getMetadata());
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
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            this.capturedMessages = new ArrayList<>(messages);
            this.capturedModelParams = new HashMap<>(modelParams);
            return new ChatResult(ChatMessage.assistant(List.of(new TextBlock("ok"))));
        }
    }

    /** Subclass that exposes setters so we can inject the connection and prompt directly. */
    private static class RecordingChatModelSetup extends BaseChatModelSetup {
        RecordingChatModelSetup(BaseChatModelConnection connection, Prompt prompt) {
            super(
                    new ResourceDescriptor(
                            RecordingChatModelSetup.class.getName(), Collections.emptyMap()),
                    null);
            this.connection = connection;
            this.prompt = prompt;
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
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

        ChatMessage userMessage = ChatMessage.user("hello").withMetadata(Map.of("key", "value"));
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

        ChatMessage toolResponse =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call", List.of(new TextBlock("tool result {key}")), false));
        setup.chat(List.of(toolResponse), Map.of("key", "v1"), Map.of());
        assertEquals(2, connection.capturedMessages.size());
        assertEquals("Task: v1", connection.capturedMessages.get(0).getText());
        assertEquals(
                "tool result {key}",
                ((ToolResultBlock) connection.capturedMessages.get(1).getBlocks().get(0))
                        .getText());
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

        ChatResult response =
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

        ChatResult response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertTrue(response.getText().length() > 0);
    }
}
