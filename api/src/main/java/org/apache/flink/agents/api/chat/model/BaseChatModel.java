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
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.List;
import java.util.function.BiFunction;

public abstract class BaseChatModel extends Resource {
    protected final BiFunction<String, ResourceType, Resource> getResource;
    protected String promptName;
    protected List<String> toolNames;

    public BaseChatModel(BiFunction<String, ResourceType, Resource> getResource) {
        this(getResource, null, null);
    }

    public BaseChatModel(
            BiFunction<String, ResourceType, Resource> getResource, String promptName) {
        this(getResource, promptName, null);
    }

    public BaseChatModel(
            BiFunction<String, ResourceType, Resource> getResource, List<String> toolNames) {
        this(getResource, null, toolNames);
    }

    public BaseChatModel(
            BiFunction<String, ResourceType, Resource> getResource,
            String promptName,
            List<String> toolNames) {
        this.getResource = getResource;
        this.promptName = promptName;
        this.toolNames = toolNames;
    }

    /**
     * Process a chat request and return a chat response.
     *
     * @param messages the input chat messages
     * @return the chat response containing model outputs
     */
    public abstract ChatMessage chat(List<ChatMessage> messages);

    @Override
    public ResourceType getResourceType() {
        return ResourceType.CHAT_MODEL;
    }
}
