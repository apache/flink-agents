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

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Abstraction of chat model connection.
 *
 * <p>Responsible for managing model service connection configurations, such as Service address, API
 * key, Connection timeout, Model name, Authentication information, etc
 */
public abstract class BaseChatModelConnection extends Resource {

    public BaseChatModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.CHAT_MODEL_CONNECTION;
    }

    /**
     * Process a chat request and return a chat response.
     *
     * @param messages the input chat messages
     * @param tools the tools can be called by the model
     * @param arguments the additional arguments passed to the model
     * @return the chat response containing model outputs
     */
    public abstract ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments);
}
