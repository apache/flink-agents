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
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.tools.Tool;
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
import static org.mockito.Mockito.*;

public class PythonChatModelConnectionTest {
    @Mock private PythonResourceAdapter mockAdapter;

    @Mock private PyObject mockChatModel;

    @Mock private ResourceDescriptor mockDescriptor;

    @Mock private BiFunction<String, ResourceType, Resource> mockGetResource;

    private PythonChatModelConnection pythonChatModelConnection;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        pythonChatModelConnection =
                new PythonChatModelConnection(
                        mockAdapter, mockChatModel, mockDescriptor, mockGetResource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testConstructor() {
        assertThat(pythonChatModelConnection).isNotNull();
        assertThat(pythonChatModelConnection.getPythonResource()).isEqualTo(mockChatModel);
    }

    @Test
    void testGetPythonResourceWithNullChatModel() {
        PythonChatModelConnection connectionWithNullModel =
                new PythonChatModelConnection(mockAdapter, null, mockDescriptor, mockGetResource);

        Object result = connectionWithNullModel.getPythonResource();

        assertThat(result).isNull();
    }

    @Test
    void testChat() {
        ChatMessage inputMessage = mock(ChatMessage.class);
        ChatMessage outputMessage = mock(ChatMessage.class);
        Tool mockTool = mock(Tool.class);
        List<ChatMessage> messages = Collections.singletonList(inputMessage);
        List<Tool> tools = Collections.singletonList(mockTool);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("temperature", 0.7);
        arguments.put("max_tokens", 100);

        Object pythonInputMessage = new Object();
        Object pythonOutputMessage = new Object();
        Object pythonTool = new Object();

        when(mockAdapter.toPythonChatMessage(inputMessage)).thenReturn(pythonInputMessage);
        when(mockAdapter.convertToPythonTool(mockTool)).thenReturn(pythonTool);
        when(mockAdapter.callMethod(eq(mockChatModel), eq("chat"), any(Map.class)))
                .thenReturn(pythonOutputMessage);
        when(mockAdapter.fromPythonChatMessage(pythonOutputMessage)).thenReturn(outputMessage);

        ChatMessage result = pythonChatModelConnection.chat(messages, tools, arguments);

        assertThat(result).isEqualTo(outputMessage);

        verify(mockAdapter).toPythonChatMessage(inputMessage);
        verify(mockAdapter).convertToPythonTool(mockTool);
        verify(mockAdapter)
                .callMethod(
                        eq(mockChatModel),
                        eq("chat"),
                        argThat(
                                kwargs -> {
                                    assertThat(kwargs).containsKey("messages");
                                    assertThat(kwargs).containsKey("tools");
                                    assertThat(kwargs).containsKey("temperature");
                                    assertThat(kwargs).containsKey("max_tokens");
                                    assertThat(kwargs.get("temperature")).isEqualTo(0.7);
                                    assertThat(kwargs.get("max_tokens")).isEqualTo(100);

                                    List<?> pythonMessages = (List<?>) kwargs.get("messages");
                                    assertThat(pythonMessages).hasSize(1);
                                    assertThat(pythonMessages.get(0)).isEqualTo(pythonInputMessage);

                                    List<?> pythonTools = (List<?>) kwargs.get("tools");
                                    assertThat(pythonTools).hasSize(1);
                                    assertThat(pythonTools.get(0)).isEqualTo(pythonTool);

                                    return true;
                                }));
        verify(mockAdapter).fromPythonChatMessage(pythonOutputMessage);
    }

    @Test
    void testInheritanceFromBaseChatModelConnection() {
        assertThat(pythonChatModelConnection).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    void testImplementsPythonResourceWrapper() {
        assertThat(pythonChatModelConnection).isInstanceOf(PythonResourceWrapper.class);
    }

    @Test
    void testConstructorWithAllNullParameters() {
        PythonChatModelConnection connectionWithNulls =
                new PythonChatModelConnection(null, null, null, null);

        assertThat(connectionWithNulls).isNotNull();
        assertThat(connectionWithNulls.getPythonResource()).isNull();
    }
}
