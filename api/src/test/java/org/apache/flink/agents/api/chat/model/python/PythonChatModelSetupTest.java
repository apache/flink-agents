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
package org.apache.flink.agents.api.chat.model.python;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.object.PyObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class PythonChatModelSetupTest {
    @Mock private PythonResourceAdapter mockAdapter;

    @Mock private PyObject mockChatModelSetup;

    @Mock private ResourceDescriptor mockDescriptor;

    @Mock private BiFunction<String, ResourceType, Resource> mockGetResource;

    private PythonChatModelSetup pythonChatModelSetup;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        pythonChatModelSetup =
                new PythonChatModelSetup(
                        mockAdapter, mockChatModelSetup, mockDescriptor, mockGetResource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testConstructor() {
        assertThat(pythonChatModelSetup).isNotNull();
        assertThat(pythonChatModelSetup.getPythonResource()).isEqualTo(mockChatModelSetup);
    }

    @Test
    void testGetPythonResourceWithNullChatModelSetup() {
        PythonChatModelSetup setupWithNullModel =
                new PythonChatModelSetup(mockAdapter, null, mockDescriptor, mockGetResource);

        Object result = setupWithNullModel.getPythonResource();

        assertThat(result).isNull();
    }

    @Test
    void testGetParameters() {
        Map<String, Object> result = pythonChatModelSetup.getParameters();

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void testChat() {
        ChatMessage inputMessage = mock(ChatMessage.class);
        ChatMessage outputMessage = mock(ChatMessage.class);
        List<ChatMessage> messages = Collections.singletonList(inputMessage);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", 0.7);
        parameters.put("max_tokens", 100);

        Object pythonInputMessage = new Object();
        Object pythonOutputMessage = new Object();

        when(mockAdapter.invoke(PythonChatModelSetup.FROM_JAVA_CHAT_MESSAGE, inputMessage))
                .thenReturn(pythonInputMessage);
        when(mockAdapter.callMethod(eq(mockChatModelSetup), eq("chat"), any(Map.class)))
                .thenReturn(pythonOutputMessage);
        when(mockAdapter.invoke(PythonChatModelSetup.TO_JAVA_CHAT_MESSAGE, pythonOutputMessage))
                .thenReturn(outputMessage);

        ChatMessage result = pythonChatModelSetup.chat(messages, parameters);

        assertThat(result).isEqualTo(outputMessage);
        verify(mockAdapter).invoke(PythonChatModelSetup.FROM_JAVA_CHAT_MESSAGE, inputMessage);
        verify(mockAdapter)
                .callMethod(
                        eq(mockChatModelSetup),
                        eq("chat"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("messages");
                                    assertThat(kwargs).containsKey("temperature");
                                    assertThat(kwargs).containsKey("max_tokens");
                                    assertThat(kwargs.get("temperature")).isEqualTo(0.7);
                                    assertThat(kwargs.get("max_tokens")).isEqualTo(100);
                                    List<?> pythonMessages = (List<?>) kwargs.get("messages");
                                    assertThat(pythonMessages).hasSize(1);
                                    assertThat(pythonMessages.get(0)).isEqualTo(pythonInputMessage);
                                    return true;
                                }));
        verify(mockAdapter).invoke(PythonChatModelSetup.TO_JAVA_CHAT_MESSAGE, pythonOutputMessage);
    }

    @Test
    void testChatWithNullChatModelSetupThrowsException() {
        PythonChatModelSetup setupWithNullModel =
                new PythonChatModelSetup(mockAdapter, null, mockDescriptor, mockGetResource);

        ChatMessage inputMessage = mock(ChatMessage.class);
        List<ChatMessage> messages = Collections.singletonList(inputMessage);
        Map<String, Object> parameters = new HashMap<>();

        assertThatThrownBy(() -> setupWithNullModel.chat(messages, parameters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ChatModelSetup is not initialized")
                .hasMessageContaining("Cannot perform chat operation");
    }

    @Test
    void testInheritanceFromBaseChatModelSetup() {
        assertThat(pythonChatModelSetup)
                .isInstanceOf(org.apache.flink.agents.api.chat.model.BaseChatModelSetup.class);
    }

    @Test
    void testImplementsPythonResourceWrapper() {
        assertThat(pythonChatModelSetup)
                .isInstanceOf(
                        org.apache.flink.agents.api.resource.python.PythonResourceWrapper.class);
    }
}
