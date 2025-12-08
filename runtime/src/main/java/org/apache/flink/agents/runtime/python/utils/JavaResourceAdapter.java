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
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import pemja.core.PythonInterpreter;

import javax.naming.OperationNotSupportedException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adapter for managing Java resources and facilitating Python-Java interoperability. */
public class JavaResourceAdapter {
    private final Map<ResourceType, Map<String, ResourceProvider>> resourceProviders;

    private final transient PythonInterpreter interpreter;

    /** Cache for instantiated resources. */
    private final transient Map<ResourceType, Map<String, Resource>> resourceCache;

    public JavaResourceAdapter(AgentPlan agentPlan, PythonInterpreter interpreter) {
        this.resourceProviders = agentPlan.getResourceProviders();
        this.interpreter = interpreter;
        this.resourceCache = new ConcurrentHashMap<>();
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
        return getResource(name, ResourceType.fromValue(typeValue));
    }

    /**
     * Retrieves a Java resource by name and type.
     *
     * @param name the name of the resource to retrieve
     * @param type the type of the resource
     * @return the resource
     * @throws Exception if the resource cannot be retrieved
     */
    public Resource getResource(String name, ResourceType type) throws Exception {
        if (resourceCache.containsKey(type) && resourceCache.get(type).containsKey(name)) {
            return resourceCache.get(type).get(name);
        }

        if (!resourceProviders.containsKey(type)
                || !resourceProviders.get(type).containsKey(name)) {
            throw new IllegalArgumentException("Resource not found: " + name + " of type " + type);
        }

        ResourceProvider provider = resourceProviders.get(type).get(name);
        if (provider instanceof PythonResourceProvider) {
            // TODO: Support getting resources from PythonResourceProvider in JavaResourceAdapter.
            throw new OperationNotSupportedException("PythonResourceProvider is not supported.");
        }

        Resource resource =
                provider.provide(
                        (String anotherName, ResourceType anotherType) -> {
                            try {
                                return this.getResource(anotherName, anotherType);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        // Cache the resource
        resourceCache.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(name, resource);

        return resource;
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
