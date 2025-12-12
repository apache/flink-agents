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
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Python-based implementation of ChatModelSetup that bridges Java and Python chat model
 * functionality. This class wraps a Python chat model setup object and provides Java interface
 * compatibility while delegating actual chat operations to the underlying Python implementation.
 */
public class PythonChatModelSetup extends BaseChatModelSetup implements PythonResourceWrapper {
    static final String FROM_JAVA_CHAT_MESSAGE = "python_java_utils.from_java_chat_message";

    static final String TO_JAVA_CHAT_MESSAGE = "python_java_utils.to_java_chat_message";

    private final PyObject chatModelSetup;
    private final PythonResourceAdapter adapter;

    public PythonChatModelSetup(
            PythonResourceAdapter adapter,
            PyObject chatModelSetup,
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.chatModelSetup = chatModelSetup;
        this.adapter = adapter;
    }

    @Override
    public ChatMessage chat(List<ChatMessage> messages, Map<String, Object> parameters) {
        checkState(
                chatModelSetup != null,
                "ChatModelSetup is not initialized. Cannot perform chat operation.");

        Map<String, Object> kwargs = new HashMap<>(parameters);

        List<Object> pythonMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            pythonMessages.add(adapter.toPythonChatMessage(message));
        }

        kwargs.put("messages", pythonMessages);

        Object pythonMessageResponse = adapter.callMethod(chatModelSetup, "chat", kwargs);
        return adapter.fromPythonChatMessage(pythonMessageResponse);
    }

    @Override
    public Object getPythonResource() {
        return chatModelSetup;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of();
    }
}
