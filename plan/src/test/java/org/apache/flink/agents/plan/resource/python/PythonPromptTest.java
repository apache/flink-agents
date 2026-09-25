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
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.UrlSource;
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
        // The exact shape the Python side produces: LocalPrompt.model_dump() serializes each
        // template ChatMessage with a `blocks` list (no `content` field) and keeps absent
        // optional media fields as explicit nulls.
        Map<String, Object> systemMessage =
                messageDump("system", List.of(textBlockDump("You are a helpful assistant.")));
        Map<String, Object> userMessage =
                messageDump(
                        "user",
                        List.of(
                                textBlockDump("Hello! What's in {subject}?"),
                                imageBlockDump("image/png", "https://example.org/cat.png")));

        List<Map<String, Object>> messageList = new ArrayList<>();
        messageList.add(systemMessage);
        messageList.add(userMessage);

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("template", messageList);

        PythonPrompt prompt = PythonPrompt.fromSerializedMap(serialized);

        assertThat(prompt).isNotNull();

        // The restored prompt formats text blocks and passes media blocks through untouched.
        Map<String, String> kwargs = new HashMap<>();
        kwargs.put("subject", "this picture");
        List<ChatMessage> formattedMessages = prompt.formatMessages(MessageRole.SYSTEM, kwargs);
        assertThat(formattedMessages).hasSize(2);
        assertThat(formattedMessages.get(0).getRole()).isEqualTo(MessageRole.SYSTEM);
        assertThat(formattedMessages.get(0).getText()).isEqualTo("You are a helpful assistant.");
        assertThat(formattedMessages.get(1).getRole()).isEqualTo(MessageRole.USER);
        assertThat(formattedMessages.get(1).getText()).isEqualTo("Hello! What's in this picture?");
        assertThat(formattedMessages.get(1).getBlocks()).hasSize(2);
        assertThat(formattedMessages.get(1).getBlocks().get(1))
                .isInstanceOf(ImageBlock.class)
                .satisfies(
                        block -> {
                            ImageBlock image = (ImageBlock) block;
                            assertThat(image.getMediaType()).isEqualTo("image/png");
                            assertThat(image.getSource())
                                    .isEqualTo(new UrlSource("https://example.org/cat.png"));
                        });
    }

    private static Map<String, Object> messageDump(String role, List<Map<String, Object>> blocks) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("blocks", blocks);
        message.put("tool_calls", new ArrayList<>());
        message.put("extra_args", new HashMap<>());
        return message;
    }

    private static Map<String, Object> textBlockDump(String text) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> imageBlockDump(String mediaType, String url) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "image");
        block.put("media_type", mediaType);
        Map<String, Object> source = new HashMap<>();
        source.put("type", "url");
        source.put("url", url);
        block.put("source", source);
        block.put("name", null);
        block.put("size_bytes", null);
        block.put("sha256", null);
        return block;
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
