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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptSpecTest {

    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void promptMessageDefaultsToUser() throws Exception {
        PromptMessage msg = M.readValue("content: hi\n", PromptMessage.class);
        assertThat(msg.getRole()).isEqualTo(MessageRole.USER);
        assertThat(msg.getContent()).isEqualTo("hi");
    }

    @Test
    void promptWithText() throws Exception {
        PromptSpec spec = M.readValue("name: p1\ntext: \"hello {x}\"\n", PromptSpec.class);
        assertThat(spec.getName()).isEqualTo("p1");
        assertThat(spec.getText()).isEqualTo("hello {x}");
        assertThat(spec.getMessages()).isNull();
    }

    @Test
    void promptWithMessages() throws Exception {
        String yaml =
                "name: p1\n"
                        + "messages:\n"
                        + "  - {role: system, content: be brief}\n"
                        + "  - {role: user, content: \"{q}\"}\n";
        PromptSpec spec = M.readValue(yaml, PromptSpec.class);
        assertThat(spec.getText()).isNull();
        assertThat(spec.getMessages()).hasSize(2);
        assertThat(spec.getMessages().get(0).getRole()).isEqualTo(MessageRole.SYSTEM);
        assertThat(spec.getMessages().get(1).getContent()).isEqualTo("{q}");
    }

    @Test
    void rejectsBothTextAndMessages() {
        String yaml = "name: p1\ntext: x\nmessages: [{content: y}]\n";
        assertThatThrownBy(() -> M.readValue(yaml, PromptSpec.class))
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsNeitherTextNorMessages() {
        assertThatThrownBy(() -> M.readValue("name: p1\n", PromptSpec.class))
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsEmptyTextOrEmptyMessages() {
        assertThatThrownBy(() -> M.readValue("name: p1\ntext: \"\"\n", PromptSpec.class))
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> M.readValue("name: p1\nmessages: []\n", PromptSpec.class))
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsUnknownProperty() {
        assertThatThrownBy(() -> M.readValue("name: p1\ntext: x\nextra: y\n", PromptSpec.class))
                .hasMessageContaining("extra");
    }

    @Test
    void promptMessageWithContentBlocks() throws Exception {
        String yaml =
                "content:\n"
                        + "  - {type: text, text: \"Describe {subject}.\"}\n"
                        + "  - type: image\n"
                        + "    media_type: image/png\n"
                        + "    source: {type: url, url: \"https://example.com/cat.png\"}\n";
        PromptMessage msg = M.readValue(yaml, PromptMessage.class);
        assertThat(msg.getContent()).isNull();
        assertThat(msg.getBlocks()).hasSize(2);
        assertThat(((TextBlock) msg.getBlocks().get(0)).getText()).isEqualTo("Describe {subject}.");
        ImageBlock image = (ImageBlock) msg.getBlocks().get(1);
        assertThat(image.getMediaType()).isEqualTo("image/png");
        assertThat(((UrlSource) image.getSource()).getUrl())
                .isEqualTo("https://example.com/cat.png");
    }

    @Test
    void rejectsEmptyContentBlockList() {
        assertThatThrownBy(() -> M.readValue("content: []\n", PromptMessage.class))
                .hasMessageContaining("must not be an empty list of content blocks");
    }

    @Test
    void rejectsNonDataContentBlocks() {
        String yaml = "content:\n  - {type: reasoning, text: thinking}\n";
        assertThatThrownBy(() -> M.readValue(yaml, PromptMessage.class))
                .hasMessageContaining(
                        "must be text, image, audio, video or document blocks, not reasoning");
    }

    @Test
    void rejectsMalformedContentBlocks() {
        assertThatThrownBy(
                        () ->
                                M.readValue(
                                        "content:\n  - {type: image, media_type: image/png}\n",
                                        PromptMessage.class))
                .hasMessageContaining("invalid content block in prompt message");
        assertThatThrownBy(() -> M.readValue("content: {text: hi}\n", PromptMessage.class))
                .hasMessageContaining("must be text or a list of content blocks");
        assertThatThrownBy(() -> M.readValue("content: null\n", PromptMessage.class))
                .hasMessageContaining("must be text or a list of content blocks");
    }
}
