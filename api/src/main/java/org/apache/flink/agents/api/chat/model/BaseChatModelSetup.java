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
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseChatModelSetup extends Resource {
    protected final String connectionName;
    protected String model;
    protected Object prompt;
    protected List<String> toolNames;
    @Nullable protected List<String> skills;
    @Nullable protected String skillDiscoveryPrompt;
    protected List<String> allowedCommands;
    protected List<String> allowedScriptDirs;

    @Nullable protected BaseChatModelConnection connection;
    protected final List<Tool> tools = new ArrayList<>();

    public BaseChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.connectionName = descriptor.getArgument("connection");
        this.model = descriptor.getArgument("model");
        this.prompt = descriptor.getArgument("prompt");
        this.toolNames = descriptor.getArgument("tools");
        this.skills = descriptor.getArgument("skills");
        List<String> declaredCommands = descriptor.getArgument("allowed_commands");
        this.allowedCommands =
                declaredCommands == null ? new ArrayList<>() : new ArrayList<>(declaredCommands);
        List<String> declaredScriptDirs = descriptor.getArgument("allowed_script_dirs");
        this.allowedScriptDirs =
                declaredScriptDirs == null
                        ? new ArrayList<>()
                        : new ArrayList<>(declaredScriptDirs);
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
                        this.resourceContext.getResource(
                                this.connectionName, ResourceType.CHAT_MODEL_CONNECTION);
        if (this.prompt != null && this.prompt instanceof String) {
            this.prompt =
                    this.resourceContext.getResource((String) this.prompt, ResourceType.PROMPT);
        }
        if (this.skills != null) {
            this.skillDiscoveryPrompt =
                    this.resourceContext.generateAvailableSkillsPrompt(this.skills);
            List<String> mutable =
                    this.toolNames == null ? new ArrayList<>() : new ArrayList<>(this.toolNames);
            if (!mutable.contains(Skills.LOAD_SKILL_TOOL)) {
                mutable.add(Skills.LOAD_SKILL_TOOL);
            }
            if (!mutable.contains(Skills.BASH_TOOL)) {
                mutable.add(Skills.BASH_TOOL);
            }
            this.toolNames = mutable;
        }
        if (this.toolNames != null) {
            for (String name : this.toolNames) {
                this.tools.add((Tool) this.resourceContext.getResource(name, ResourceType.TOOL));
            }
        }
    }

    public abstract Map<String, Object> getParameters();

    /**
     * Record token usage metrics for the given model on this setup's bound metric group.
     *
     * @param modelName the name of the model used
     * @param promptTokens the number of prompt tokens
     * @param completionTokens the number of completion tokens
     */
    public void recordTokenMetrics(String modelName, long promptTokens, long completionTokens) {
        FlinkAgentsMetricGroup metricGroup = getMetricGroup();
        if (metricGroup == null) {
            return;
        }
        FlinkAgentsMetricGroup modelGroup = metricGroup.getSubGroup("model", modelName);
        modelGroup.getCounter("promptTokens").inc(promptTokens);
        modelGroup.getCounter("completionTokens").inc(completionTokens);
    }

    public ChatMessage chat(List<ChatMessage> messages) {
        return this.chat(messages, Collections.emptyMap(), Collections.emptyMap());
    }

    public ChatMessage chat(
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            Map<String, Object> modelParams) {
        Preconditions.checkNotNull(
                connection,
                "Connection is not initialized. Ensure open() is called before chat().");

        // Format input messages if set prompt.
        if (this.prompt != null) {
            Preconditions.checkState(
                    prompt instanceof Prompt,
                    "Prompt is not initialized. Ensure open() is called before chat().");
            Prompt prompt = (Prompt) this.prompt;
            Map<String, String> stringified = new HashMap<>();
            if (promptArgs != null) {
                for (Map.Entry<String, Object> entry : promptArgs.entrySet()) {
                    stringified.put(
                            entry.getKey(),
                            entry.getValue() != null ? entry.getValue().toString() : "");
                }
            }

            // append meaningful messages
            List<ChatMessage> promptMessages = prompt.formatMessages(MessageRole.USER, stringified);
            for (ChatMessage message : messages) {
                if ((message.getContent() != null && !message.getContent().isEmpty())
                        || message.getRole() == MessageRole.ASSISTANT) {
                    promptMessages.add(message);
                }
            }
            messages = promptMessages;
        }

        if (this.skillDiscoveryPrompt != null && !this.skillDiscoveryPrompt.isEmpty()) {
            int idx = ChatMessage.findFirstSystemMessage(messages);
            List<ChatMessage> mutated = new ArrayList<>(messages);
            mutated.add(idx + 1, new ChatMessage(MessageRole.SYSTEM, this.skillDiscoveryPrompt));
            messages = mutated;
        }

        Map<String, Object> params = this.getParameters();
        if (modelParams != null) {
            params.putAll(modelParams);
        }
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

    @Nullable
    public List<String> getSkills() {
        return skills;
    }

    @Nullable
    public String getSkillDiscoveryPrompt() {
        return skillDiscoveryPrompt;
    }

    public List<String> getAllowedCommands() {
        return allowedCommands;
    }

    public List<String> getAllowedScriptDirs() {
        return allowedScriptDirs;
    }
}
