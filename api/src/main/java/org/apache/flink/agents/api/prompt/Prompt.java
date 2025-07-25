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

import org.apache.flink.agents.api.chat.ChatOptions;
import org.apache.flink.agents.api.chat.messages.Message;
import org.apache.flink.agents.api.chat.messages.SystemMessage;
import org.apache.flink.agents.api.chat.messages.UserMessage;
import org.apache.flink.agents.api.chat.model.ChatRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A simplified Prompt class that represents a conversation prompt for AI models. Contains a list of
 * messages and optional chat options.
 */
public class Prompt implements ChatRequest<List<Message>> {

    private final List<Message> messages;
    private final ChatOptions chatOptions;

    /** Create a prompt with a single user message. */
    public Prompt(String userMessage) {
        this(new UserMessage(userMessage));
    }

    /** Create a prompt with a single message. */
    public Prompt(Message message) {
        this(Collections.singletonList(message), null);
    }

    /** Create a prompt with multiple messages. */
    public Prompt(List<Message> messages) {
        this(messages, null);
    }

    /** Create a prompt with multiple messages (varargs). */
    public Prompt(Message... messages) {
        this(Arrays.asList(messages), null);
    }

    /** Create a prompt with messages and chat options. */
    public Prompt(List<Message> messages, ChatOptions chatOptions) {
        this.messages =
                new ArrayList<>(Objects.requireNonNull(messages, "messages cannot be null"));
        this.chatOptions = chatOptions;

        if (this.messages.isEmpty()) {
            throw new IllegalArgumentException("Prompt must contain at least one message");
        }
    }

    /** Create a prompt with a user message and chat options. */
    public Prompt(String userMessage, ChatOptions chatOptions) {
        this(new UserMessage(userMessage), chatOptions);
    }

    /** Create a prompt with a single message and chat options. */
    public Prompt(Message message, ChatOptions chatOptions) {
        this(Collections.singletonList(message), chatOptions);
    }

    @Override
    public ChatOptions getOptions() {
        return chatOptions;
    }

    @Override
    public List<Message> getInstructions() {
        return Collections.unmodifiableList(messages);
    }

    /** Get all messages in the prompt. */
    public List<Message> getMessages() {
        return getInstructions();
    }

    /** Get the first system message in the prompt, if any. */
    public Optional<SystemMessage> getSystemMessage() {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .findFirst();
    }

    /** Get the last user message in the prompt, if any. */
    public Optional<UserMessage> getLastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage) {
                return Optional.of((UserMessage) message);
            }
        }
        return Optional.empty();
    }

    /** Get all user messages in the prompt. */
    public List<UserMessage> getUserMessages() {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .collect(java.util.stream.Collectors.toList());
    }

    /** Get the combined text content of all messages. */
    public String getContentAsText() {
        return messages.stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** Create a new prompt with an additional message. */
    public Prompt withMessage(Message message) {
        List<Message> newMessages = new ArrayList<>(messages);
        newMessages.add(message);
        return new Prompt(newMessages, chatOptions);
    }

    /** Create a new prompt with additional messages. */
    public Prompt withMessages(Message... additionalMessages) {
        List<Message> newMessages = new ArrayList<>(messages);
        newMessages.addAll(Arrays.asList(additionalMessages));
        return new Prompt(newMessages, chatOptions);
    }

    /** Create a new prompt with different chat options. */
    public Prompt withOptions(ChatOptions newOptions) {
        return new Prompt(messages, newOptions);
    }

    /** Get the number of messages in the prompt. */
    public int size() {
        return messages.size();
    }

    /** Check if the prompt is empty. */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Prompt prompt = (Prompt) o;
        return Objects.equals(messages, prompt.messages)
                && Objects.equals(chatOptions, prompt.chatOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messages, chatOptions);
    }

    @Override
    public String toString() {
        return "Prompt{"
                + "messages="
                + messages.size()
                + ", hasOptions="
                + (chatOptions != null)
                + '}';
    }

    /** Builder for creating prompts fluently. */
    public static class Builder {
        private final List<Message> messages = new ArrayList<>();
        private ChatOptions chatOptions;

        public Builder systemMessage(String content) {
            messages.add(new SystemMessage(content));
            return this;
        }

        public Builder userMessage(String content) {
            messages.add(new UserMessage(content));
            return this;
        }

        public Builder message(Message message) {
            messages.add(message);
            return this;
        }

        public Builder options(ChatOptions options) {
            this.chatOptions = options;
            return this;
        }

        public Prompt build() {
            return new Prompt(messages, chatOptions);
        }
    }

    /** Create a new builder for fluent prompt creation. */
    public static Builder builder() {
        return new Builder();
    }
}
