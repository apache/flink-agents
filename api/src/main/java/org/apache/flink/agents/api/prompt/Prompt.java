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

package org.apache.flink.agents.api.prompt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Abstract base class for prompts.
 *
 * <p>This is the base class for all prompt implementations in Flink Agents. Subclasses must
 * implement the formatting methods to generate text or messages from templates.
 *
 * <p>Common implementations:
 *
 * <ul>
 *   <li>Template-based prompts with placeholders (via {@link #fromText} or {@link #fromMessages})
 *   <li>{@link org.apache.flink.agents.integrations.mcp.MCPPrompt} - Dynamic prompts from MCP
 *       servers
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // String template
 * Prompt prompt = Prompt.fromText("Hello {name}!");
 * String result = prompt.formatString(Map.of("name", "World"));
 * // result: "Hello World!"
 *
 * // Message template
 * List<ChatMessage> messages = List.of(
 *     new ChatMessage(MessageRole.SYSTEM, "You are {role}"),
 *     new ChatMessage(MessageRole.USER, "Help me with {task}")
 * );
 * Prompt prompt2 = Prompt.fromMessages(messages);
 * List<ChatMessage> result2 = prompt2.formatMessages(
 *     MessageRole.SYSTEM,
 *     Map.of("role", "an assistant", "task", "coding")
 * );
 * }</pre>
 *
 * @see org.apache.flink.agents.integrations.mcp.MCPPrompt
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({@JsonSubTypes.Type(value = Prompt.LocalPrompt.class, name = "LocalPrompt")})
public abstract class Prompt extends SerializableResource {

    /**
     * Create a prompt from a text string template.
     *
     * @param text The text template with placeholders like {variable}
     * @return A Prompt instance
     */
    public static Prompt fromText(String text) {
        return new LocalPrompt(text);
    }

    /**
     * Create a prompt from a sequence of chat messages.
     *
     * @param messages The list of chat messages forming the prompt template
     * @return A Prompt instance
     */
    public static Prompt fromMessages(List<ChatMessage> messages) {
        return new LocalPrompt(messages);
    }

    /**
     * Generate a text string from the prompt template with additional arguments.
     *
     * @param kwargs Key-value pairs to substitute in the template
     * @return The formatted prompt as a string
     */
    public abstract String formatString(Map<String, String> kwargs);

    /**
     * Generate a list of ChatMessage from the prompt template with additional arguments.
     *
     * @param defaultRole The default message role (usually SYSTEM)
     * @param kwargs Key-value pairs to substitute in the template
     * @return List of formatted chat messages
     */
    public abstract List<ChatMessage> formatMessages(
            MessageRole defaultRole, Map<String, String> kwargs);

    @JsonIgnore
    @Override
    public ResourceType getResourceType() {
        return ResourceType.PROMPT;
    }

    /**
     * Local prompt implementation for language models.
     *
     * <p>This prompt implementation uses a local template that can be either a string or a sequence
     * of ChatMessage objects. The template supports placeholder substitution using {variable}
     * syntax.
     *
     * <p>While this class is public, users should prefer using {@link Prompt#fromText(String)} or
     * {@link Prompt#fromMessages(List)} factory methods to create prompt instances instead of
     * directly instantiating this class.
     */
    public static class LocalPrompt extends Prompt {
        private static final String FIELD_TEMPLATE = "template";

        @JsonProperty(FIELD_TEMPLATE)
        private final transient PromptTemplate template;

        @JsonCreator
        private LocalPrompt(@JsonProperty(FIELD_TEMPLATE) PromptTemplate promptTemplate) {
            this.template = promptTemplate;
        }

        public LocalPrompt(String template) {
            this.template = PromptTemplate.fromString(template);
        }

        public LocalPrompt(List<ChatMessage> template) {
            this.template = PromptTemplate.fromMessages(template);
        }

        @Override
        public String formatString(Map<String, String> kwargs) {
            return template.match(
                    // Handle string template
                    content -> format(content, kwargs),
                    // Handle messages template
                    messages -> {
                        List<String> formattedMessages = new ArrayList<>();
                        for (ChatMessage message : messages) {
                            String formattedContent = format(message.getContent(), kwargs);
                            String formatted =
                                    message.getRole().getValue() + ": " + formattedContent;
                            formattedMessages.add(formatted);
                        }
                        return String.join("\n", formattedMessages);
                    });
        }

        @Override
        public List<ChatMessage> formatMessages(
                MessageRole defaultRole, Map<String, String> kwargs) {
            return template.match(
                    // Handle string template
                    content ->
                            new ArrayList<>(
                                    Collections.singletonList(
                                            new ChatMessage(defaultRole, format(content, kwargs)))),
                    // Handle messages template
                    messages ->
                            messages.stream()
                                    .map(
                                            message ->
                                                    new ChatMessage(
                                                            message.getRole(),
                                                            format(message.getContent(), kwargs)))
                                    .collect(Collectors.toList()));
        }

        /** Format template string with keyword arguments */
        private static String format(String template, Map<String, String> kwargs) {
            if (template == null) {
                return "";
            }

            String result = template;
            for (Map.Entry<String, String> entry : kwargs.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                String value = entry.getValue() != null ? entry.getValue() : "";
                result = result.replace(placeholder, value);
            }
            return result;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
        private abstract static class PromptTemplate {
            public static PromptTemplate fromString(String content) {
                return new StringTemplate(content);
            }

            public static PromptTemplate fromMessages(List<ChatMessage> messages) {
                return new MessagesTemplate(messages);
            }

            /**
             * Pattern matching method for type-safe operations. This replaces instanceof checks and
             * casting.
             */
            public abstract <T> T match(
                    Function<String, T> onString, Function<List<ChatMessage>, T> onMessages);
        }

        /** String template implementation. */
        private static class StringTemplate extends PromptTemplate {
            private static final String FIELD_CONTENT = "content";

            @JsonProperty(FIELD_CONTENT)
            private final String content;

            @JsonCreator
            public StringTemplate(@JsonProperty(FIELD_CONTENT) String content) {
                this.content = Objects.requireNonNull(content, "content cannot be null");
            }

            public String getContent() {
                return content;
            }

            @Override
            public <T> T match(
                    Function<String, T> onString, Function<List<ChatMessage>, T> onMessages) {
                return onString.apply(content);
            }

            @Override
            public String toString() {
                return "StringTemplate{content='" + content + "'}";
            }
        }

        /** Messages template implementation. */
        private static class MessagesTemplate extends PromptTemplate {
            private static final String FIELD_MESSAGES = "messages";

            @JsonProperty(FIELD_MESSAGES)
            private final List<ChatMessage> messages;

            @JsonCreator
            public MessagesTemplate(@JsonProperty(FIELD_MESSAGES) List<ChatMessage> messages) {
                Objects.requireNonNull(messages, "messages cannot be null");
                if (messages.isEmpty()) {
                    throw new IllegalArgumentException("Messages cannot be empty");
                }
                this.messages = new ArrayList<>(messages);
            }

            public List<ChatMessage> getMessages() {
                return new ArrayList<>(messages);
            }

            @Override
            public <T> T match(
                    Function<String, T> onString, Function<List<ChatMessage>, T> onMessages) {
                return onMessages.apply(new ArrayList<>(messages));
            }

            @Override
            public String toString() {
                return "MessagesTemplate{messages=" + messages.size() + " items}";
            }
        }
    }
}
