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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonMCPResourceTest {

    @Test
    void transfersDiscoveredObjectsToChildResources() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject serverObject = mock(PyObject.class);
        PyObject toolObject = mock(PyObject.class);
        PyObject promptObject = mock(PyObject.class);
        PythonMCPServer server =
                new PythonMCPServer(
                        adapter,
                        serverObject,
                        mock(ResourceDescriptor.class),
                        mock(ResourceContext.class));

        when(adapter.callMethod(serverObject, "list_tools", Map.of()))
                .thenReturn(List.of(toolObject));
        when(adapter.invoke("python_java_utils.get_java_tool_metadata_from_tool", toolObject))
                .thenReturn(
                        Map.of(
                                "name", "tool",
                                "description", "description",
                                "inputSchema", "{}"));
        when(adapter.callMethod(serverObject, "list_prompts", Map.of()))
                .thenReturn(List.of(promptObject));

        PythonMCPTool tool = server.listTools("server").get(0);
        PythonMCPPrompt prompt = server.listPrompts().get(0);

        verify(toolObject, never()).close();
        verify(promptObject, never()).close();

        tool.close();
        prompt.close();
        server.close();

        verify(adapter).callMethod(toolObject, "close", Map.of());
        verify(adapter).callMethod(promptObject, "close", Map.of());
        verify(adapter).callMethod(serverObject, "close", Map.of());
        verify(toolObject).close();
        verify(promptObject).close();
        verify(serverObject).close();
    }

    @Test
    void releasesPromptBridgeValuesAfterConversion() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject promptObject = mock(PyObject.class);
        PyObject nameObject = mock(PyObject.class);
        PyObject roleObject = mock(PyObject.class);
        PyObject messageObject = mock(PyObject.class);
        ChatMessage message = mock(ChatMessage.class);
        PythonMCPPrompt prompt = new PythonMCPPrompt(adapter, promptObject);

        when(promptObject.getAttr("name")).thenReturn(nameObject);
        when(nameObject.toString()).thenReturn("prompt");
        when(adapter.invoke("python_java_utils.from_java_message_role", MessageRole.USER))
                .thenReturn(roleObject);
        when(adapter.callMethod(eq(promptObject), eq("format_messages"), any(Map.class)))
                .thenReturn(List.of(messageObject));
        when(adapter.fromPythonChatMessage(messageObject)).thenReturn(message);

        assertThat(prompt.getName()).isEqualTo("prompt");
        assertThat(prompt.formatMessages(MessageRole.USER, Map.of())).containsExactly(message);

        verify(nameObject).close();
        verify(roleObject).close();
        verify(messageObject).close();

        prompt.close();
    }

    @Test
    void rollsBackTransferredChildrenWhenLaterChildCreationFails() throws Exception {
        RuntimeException failure = new RuntimeException("metadata failed");
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject serverObject = mock(PyObject.class);
        PyObject firstToolObject = mock(PyObject.class);
        PyObject secondToolObject = mock(PyObject.class);
        PythonMCPServer server =
                new PythonMCPServer(
                        adapter,
                        serverObject,
                        mock(ResourceDescriptor.class),
                        mock(ResourceContext.class));

        when(adapter.callMethod(serverObject, "list_tools", Map.of()))
                .thenReturn(List.of(firstToolObject, secondToolObject));
        when(adapter.invoke("python_java_utils.get_java_tool_metadata_from_tool", firstToolObject))
                .thenReturn(
                        Map.of(
                                "name", "first",
                                "description", "description",
                                "inputSchema", "{}"));
        when(adapter.invoke("python_java_utils.get_java_tool_metadata_from_tool", secondToolObject))
                .thenThrow(failure);

        assertThatThrownBy(() -> server.listTools("server")).isSameAs(failure);

        verify(adapter).callMethod(firstToolObject, "close", Map.of());
        InOrder closeOrder = inOrder(secondToolObject, firstToolObject);
        closeOrder.verify(secondToolObject).close();
        closeOrder.verify(firstToolObject).close();

        server.close();
    }
}
