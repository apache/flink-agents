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

package org.apache.flink.agents.api.yaml;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AliasesTest {

    @Test
    void resolveEventTypeHits() {
        assertThat(Aliases.resolveEventType("input")).isEqualTo(InputEvent.EVENT_TYPE);
        assertThat(Aliases.resolveEventType("output")).isEqualTo(OutputEvent.EVENT_TYPE);
        assertThat(Aliases.resolveEventType("chat_request")).isEqualTo(ChatRequestEvent.EVENT_TYPE);
        assertThat(Aliases.resolveEventType("chat_response"))
                .isEqualTo(ChatResponseEvent.EVENT_TYPE);
    }

    @Test
    void resolveEventTypeMissPassesThrough() {
        assertThat(Aliases.resolveEventType("_already_qualified_event"))
                .isEqualTo("_already_qualified_event");
    }

    @Test
    void clazzAliasJavaBucket() {
        String fqn =
                Aliases.resolveClazz("ollama", ResourceType.CHAT_MODEL_CONNECTION, Language.JAVA);
        assertThat(fqn).isEqualTo(ResourceName.ChatModel.OLLAMA_CONNECTION);
    }

    @Test
    void clazzAliasPythonBucket() {
        String fqn =
                Aliases.resolveClazz("ollama", ResourceType.CHAT_MODEL_CONNECTION, Language.PYTHON);
        assertThat(fqn).isEqualTo(ResourceName.ChatModel.Python.OLLAMA_CONNECTION);
    }

    @Test
    void clazzAliasMissPassesThrough() {
        String fqn =
                Aliases.resolveClazz(
                        "com.example.MyChat", ResourceType.CHAT_MODEL_CONNECTION, Language.JAVA);
        assertThat(fqn).isEqualTo("com.example.MyChat");
    }

    @Test
    void pythonWrapperLookup() {
        assertThat(Aliases.PYTHON_WRAPPER_CLAZZ.get(ResourceType.CHAT_MODEL_CONNECTION))
                .isEqualTo(ResourceName.ChatModel.PYTHON_WRAPPER_CONNECTION);
        assertThat(Aliases.PYTHON_WRAPPER_CLAZZ.get(ResourceType.VECTOR_STORE))
                .isEqualTo(ResourceName.VectorStore.PYTHON_WRAPPER_VECTOR_STORE);
    }
}
