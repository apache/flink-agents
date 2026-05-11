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
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public abstract class BaseChatModelSetup extends Resource {
    protected final String connectionName;
    protected String model;
    protected Object prompt;
    protected List<String> toolNames;

    @Nullable protected BaseChatModelConnection connection;
    protected final List<Tool> tools = new ArrayList<>();

    public BaseChatModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.connectionName = descriptor.getArgument("connection");
        this.model = descriptor.getArgument("model");
        this.prompt = descriptor.getArgument("prompt");
        this.toolNames = descriptor.getArgument("tools");
    }

    /**
     * Trigger construction for resource objects.
     *
     * <p>Currently, in cross-language invocation scenarios, constructing resource object within an
     * async thread may encounter issues. We resolved this issue by moving the construction of the
     * resources object out of the method to be async executed and invoking it in the main thread.
     */
    @Override
    public void open() throws Exception {
        this.connection =
                (BaseChatModelConnection)
                        this.getResource.apply(
                                this.connectionName, ResourceType.CHAT_MODEL_CONNECTION);
        if (this.prompt != null && this.prompt instanceof String) {
            this.prompt = this.getResource.apply((String) this.prompt, ResourceType.PROMPT);
        }
        if (this.toolNames != null) {
            for (String name : this.toolNames) {
                this.tools.add((Tool) this.getResource.apply(name, ResourceType.TOOL));
            }
        }
    }

    public abstract Map<String, Object> getParameters();

    public ChatMessage chat(List<ChatMessage> messages) {
        return this.chat(messages, Collections.emptyMap());
    }

    public ChatMessage chat(List<ChatMessage> messages, Map<String, Object> parameters) {
        Preconditions.checkNotNull(
                connection,
                "Connection is not initialized. Ensure open() is called before chat().");
        // Pass metric group to connection for token usage tracking
        connection.setMetricGroup(getMetricGroup());

        // Format input messages if set prompt.
        if (this.prompt != null) {
            Preconditions.checkState(
                    prompt instanceof Prompt,
                    "Prompt is not initialized. Ensure open() is called before chat().");
            Prompt prompt = (Prompt) this.prompt;
            Map<String, String> arguments = new HashMap<>();
            for (ChatMessage message : messages) {
                for (Map.Entry<String, Object> entry : message.getExtraArgs().entrySet()) {
                    arguments.put(entry.getKey(), entry.getValue().toString());
                }
            }

            // append meaningful messages
            List<ChatMessage> promptMessages = prompt.formatMessages(MessageRole.USER, arguments);
            for (ChatMessage message : messages) {
                if ((message.getContent() != null && !message.getContent().isEmpty())
                        || message.getRole() == MessageRole.ASSISTANT) {
                    promptMessages.add(message);
                }
            }
            messages = promptMessages;
        }

        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);
        return connection.chat(messages, tools, params);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.CHAT_MODEL;
    }

    @VisibleForTesting
    public String getConnectionName() {
        return this.connectionName;
    }

    @VisibleForTesting
    public String getModel() {
        return model;
    }

    @VisibleForTesting
    public Object getPrompt() {
        return prompt;
    }

    @VisibleForTesting
    public List<String> getToolNames() {
        return toolNames;
    }
}
