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
package org.apache.flink.agents.runtime.python.utils;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import pemja.core.PythonInterpreter;

import java.util.function.BiFunction;

/** Adapter for managing Java resources and facilitating Python-Java interoperability. */
public class JavaResourceAdapter {
    private final BiFunction<String, ResourceType, Resource> getResource;

    private final transient PythonInterpreter interpreter;

    public JavaResourceAdapter(
            BiFunction<String, ResourceType, Resource> getResource, PythonInterpreter interpreter) {
        this.getResource = getResource;
        this.interpreter = interpreter;
    }

    /**
     * Retrieves a Java resource by name and type value. This method is intended for use by the
     * Python interpreter.
     *
     * @param name the name of the resource to retrieve
     * @param typeValue the type value of the resource
     * @return the resource
     * @throws Exception if the resource cannot be retrieved
     */
    public Resource getResource(String name, String typeValue) throws Exception {
        return getResource.apply(name, ResourceType.fromValue(typeValue));
    }

    /**
     * Convert a Python chat message to a Java chat message. This method is intended for use by the
     * Python interpreter.
     *
     * @param pythonChatMessage the Python chat message
     * @return the Java chat message
     */
    public ChatMessage fromPythonChatMessage(Object pythonChatMessage) {
        // TODO: Delete this method after the pemja findClass method is fixed.
        ChatMessage chatMessage = new ChatMessage();
        if (interpreter == null) {
            throw new IllegalStateException("Python interpreter is not set.");
        }
        String roleValue =
                (String)
                        interpreter.invoke(
                                "python_java_utils.update_java_chat_message",
                                pythonChatMessage,
                                chatMessage);
        chatMessage.setRole(MessageRole.fromValue(roleValue));
        return chatMessage;
    }
}
