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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void testChatThrowsUnsupportedOperationException() {
        ChatMessage mockChatMessage = mock(ChatMessage.class);
        Tool mockTool = mock(Tool.class);
        List<ChatMessage> messages = Collections.singletonList(mockChatMessage);
        List<Tool> tools = Collections.singletonList(mockTool);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("temperature", 0.7);
        arguments.put("max_tokens", 100);

        assertThatThrownBy(() -> pythonChatModelConnection.chat(messages, tools, arguments))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(
                        "Chat method of PythonChatModelConnection cannot be called directly from Java runtime")
                .hasMessageContaining("This connection serves as a Python resource wrapper only")
                .hasMessageContaining(
                        "Chat operations should be performed on the Python side using the underlying Python chat model object");
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
