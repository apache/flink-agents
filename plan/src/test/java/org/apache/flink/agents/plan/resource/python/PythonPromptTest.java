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

package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test class for {@link PythonPrompt}. */
public class PythonPromptTest {

    @Test
    public void testFromSerializedMapWithStringTemplate() {
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("template", "Hello, {name}!");

        PythonPrompt prompt = PythonPrompt.fromSerializedMap(serialized);

        assertThat(prompt).isNotNull();
        // Test that the prompt works correctly
        Map<String, String> kwargs = new HashMap<>();
        kwargs.put("name", "Bob");
        String formatted = prompt.formatString(kwargs);
        assertThat(formatted).isEqualTo("Hello, Bob!");
    }

    @Test
    public void testFromSerializedMapWithMessageListTemplate() {
        // Create message map
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", "You are a helpful assistant.");

        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "Hello!");

        List<Map<String, Object>> messageList = new ArrayList<>();
        messageList.add(systemMessage);
        messageList.add(userMessage);

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("template", messageList);

        PythonPrompt prompt = PythonPrompt.fromSerializedMap(serialized);

        assertThat(prompt).isNotNull();

        // Test that the prompt formats messages correctly
        List<ChatMessage> formattedMessages =
                prompt.formatMessages(MessageRole.SYSTEM, new HashMap<>());
        assertThat(formattedMessages).hasSize(2);
        assertThat(formattedMessages.get(0).getRole()).isEqualTo(MessageRole.SYSTEM);
        assertThat(formattedMessages.get(0).getContent()).isEqualTo("You are a helpful assistant.");
        assertThat(formattedMessages.get(1).getRole()).isEqualTo(MessageRole.USER);
        assertThat(formattedMessages.get(1).getContent()).isEqualTo("Hello!");
    }

    @Test
    public void testFromSerializedMapWithMissingTemplateKey() {
        Map<String, Object> serialized = new HashMap<>();
        // Missing template key

        assertThatThrownBy(() -> PythonPrompt.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map must contain 'template' key");
    }

    @Test
    public void testFromSerializedMapWithEmptyList() {
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("template", new ArrayList<>());

        assertThatThrownBy(() -> PythonPrompt.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Template list cannot be empty");
    }

    @Test
    public void testFromSerializedMapWithInvalidTemplateType() {
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("template", 123); // Invalid type

        assertThatThrownBy(() -> PythonPrompt.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Python prompt parsing failed. Template is not a string or list.");
    }
}
