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
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Python-based implementation of ChatModelConnection that wraps a Python chat model object. This
 * class serves as a bridge between Java and Python chat model environments, but unlike {@link
 * PythonChatModelSetup}, it does not provide direct chat functionality in Java.
 */
public class PythonChatModelConnection extends BaseChatModelConnection
        implements PythonResourceWrapper {
    private PyObject chatModel;

    /**
     * Creates a new PythonChatModelConnection.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param chatModel The Python chat model object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonChatModelConnection(
            PythonResourceAdapter adapter,
            PyObject chatModel,
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.chatModel = chatModel;
    }

    @Override
    public Object getPythonResource() {
        return chatModel;
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        throw new UnsupportedOperationException(
                "Chat method of PythonChatModelConnection cannot be called directly from Java runtime. "
                        + "This connection serves as a Python resource wrapper only. "
                        + "Chat operations should be performed on the Python side using the underlying Python chat model object.");
    }
}
