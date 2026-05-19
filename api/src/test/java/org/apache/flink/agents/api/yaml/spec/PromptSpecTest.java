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
import org.apache.flink.agents.api.chat.messages.MessageRole;
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
}
