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
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class BaseChatModelSetup extends Resource {

    protected final String connectionName;
    protected String model;
    protected Object prompt;
    protected List<String> toolNames;
    protected final List<String> subagentNames;
    @Nullable protected List<String> skills;
    @Nullable protected String skillDiscoveryPrompt;
    protected List<String> allowedCommands;
    protected List<String> allowedScriptDirs;
    protected StructuredOutputStrategy structuredOutputStrategy;

    @Nullable protected BaseChatModelConnection connection;
    protected final List<Tool> tools = new ArrayList<>();

    public BaseChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.connectionName = descriptor.getArgument("connection");
        this.model = descriptor.getArgument("model");
        this.prompt = descriptor.getArgument("prompt");
        this.toolNames = descriptor.getArgument("tools");
        List<String> declaredSubagents = descriptor.getArgument("subagents");
        this.subagentNames =
                declaredSubagents == null ? new ArrayList<>() : new ArrayList<>(declaredSubagents);
        this.skills = descriptor.getArgument("skills");
        List<String> declaredCommands = descriptor.getArgument("allowed_commands");
        this.allowedCommands =
                declaredCommands == null ? new ArrayList<>() : new ArrayList<>(declaredCommands);
        List<String> declaredScriptDirs = descriptor.getArgument("allowed_script_dirs");
        this.allowedScriptDirs =
                declaredScriptDirs == null
                        ? new ArrayList<>()
                        : new ArrayList<>(declaredScriptDirs);
        this.structuredOutputStrategy =
                StructuredOutputStrategy.fromArgument(
                        descriptor.getArgument("structured_output_strategy"),
                        StructuredOutputStrategy.AUTO);
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
                    nullIfEmpty(this.resourceContext.generateAvailableSkillsPrompt(this.skills));
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
        // Rebuilt from scratch: open() may run again on the same instance, and the callables must
        // not accumulate.
        this.tools.clear();
        Set<String> callableNames = new LinkedHashSet<>();
        if (this.toolNames != null) {
            for (String name : this.toolNames) {
                Preconditions.checkState(
                        callableNames.add(name), "Duplicate callable name: %s", name);
                this.tools.add((Tool) this.resourceContext.getResource(name, ResourceType.TOOL));
            }
        }
        for (String name : this.subagentNames) {
            // Tools are forbidden to carry the reserved prefix at registration, so a prefixed
            // callable name can only come from this loop and a clash with a tool is impossible.
            // Checked before the schema below, because a name declared twice is a mistake in the
            // declaration whether or not it ends up registered.
            Preconditions.checkState(
                    callableNames.add(SubagentSetup.CALLABLE_NAME_PREFIX + name),
                    "Duplicate callable name: %s",
                    SubagentSetup.CALLABLE_NAME_PREFIX + name);
            Resource resource = this.resourceContext.getResource(name, ResourceType.AGENT);
            // A sub-agent owned by the other language resolves to a bridge handle here, which
            // carries no schema to declare, so it is rejected instead of silently dropped.
            Preconditions.checkState(
                    resource instanceof SubagentSetup,
                    "Sub-agent %s must resolve to a SubagentSetup, but was %s",
                    name,
                    resource.getClass().getName());
            SubagentSetup setup = (SubagentSetup) resource;
            String inputSchema = setup.getInputSchema();
            // A sub-agent that declares neither an input schema nor an input type gives the model
            // no arguments to build a call from, so the declaration is a mistake rather than
            // something to skip: fail the job at setup time, consistent with the duplicate-name and
            // bridge-handle checks above.
            Preconditions.checkState(
                    inputSchema != null,
                    "Sub-agent %s declares neither an input schema nor an input type, so there are"
                            + " no arguments for the model to build a call from.",
                    name);
            this.tools.add(new SubagentTool(name, setup.getDescription(), inputSchema));
        }
    }

    @Nullable
    private static String nullIfEmpty(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public abstract Map<String, Object> getParameters();

    /**
     * Record token usage metrics for the given model on the provided metric group.
     *
     * @param metricGroup the non-null metric group captured when the request was initiated
     * @param modelName the name of the model used
     * @param promptTokens the number of prompt tokens
     * @param completionTokens the number of completion tokens
     */
    public void recordTokenMetrics(
            FlinkAgentsMetricGroup metricGroup,
            String modelName,
            long promptTokens,
            long completionTokens) {
        FlinkAgentsMetricGroup modelGroup =
                Preconditions.checkNotNull(metricGroup, "Metric group must not be null.")
                        .getSubGroup("model", modelName);
        modelGroup.getCounter("promptTokens").inc(promptTokens);
        modelGroup.getCounter("completionTokens").inc(completionTokens);
    }

    /**
     * The setup's request-shaping step, shared by {@link #chat} and by the model-routing judge
     * (which must route on exactly what the selected model will receive): renders the bound {@link
     * Prompt} (if any) with the prompt args and prepends it to the non-empty conversation messages,
     * then injects the skill-discovery prompt (if any). Returns the input unchanged when neither is
     * configured.
     */
    public List<ChatMessage> prepareRequestMessages(
            List<ChatMessage> messages, Map<String, Object> promptArgs) {
        // Format input messages if set prompt. Read via the accessor so subclasses that override
        // getPrompt() are honored — the same contract the routing layer inspects.
        Object boundPrompt = getPrompt();
        if (boundPrompt != null) {
            Preconditions.checkState(
                    boundPrompt instanceof Prompt,
                    "Prompt is not initialized. Ensure open() is called before chat().");
            Prompt prompt = (Prompt) boundPrompt;
            Map<String, String> stringified = new HashMap<>();
            if (promptArgs != null) {
                for (Map.Entry<String, Object> entry : promptArgs.entrySet()) {
                    stringified.put(
                            entry.getKey(),
                            entry.getValue() != null ? entry.getValue().toString() : "");
                }
            }

            // append meaningful messages; any block counts, so image-only messages survive
            List<ChatMessage> promptMessages = prompt.formatMessages(MessageRole.USER, stringified);
            for (ChatMessage message : messages) {
                if (!message.getBlocks().isEmpty() || message.getRole() == MessageRole.ASSISTANT) {
                    promptMessages.add(message);
                }
            }
            messages = promptMessages;
        }

        if (this.skillDiscoveryPrompt != null) {
            // Right after the first system message, or at the head when there is none.
            int idx = ChatMessage.findFirstSystemMessage(messages) + 1;
            List<ChatMessage> mutated = new ArrayList<>(messages);
            mutated.add(idx, new ChatMessage(MessageRole.SYSTEM, this.skillDiscoveryPrompt));
            messages = mutated;
        }
        return messages;
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

        messages = prepareRequestMessages(messages, promptArgs);

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

    /** Returns the configured model or deployment identifier used by this setup. */
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

    /** Names of the {@code AGENT} resources this setup declares as delegable. */
    public List<String> getSubagentNames() {
        return subagentNames;
    }

    @VisibleForTesting
    public List<Tool> getTools() {
        return tools;
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

    /**
     * The configured intent about how an output schema should be applied, defaulting to {@link
     * StructuredOutputStrategy#AUTO}. Whether native structured output is actually applied combines
     * this policy with the connection's model-dependent capability.
     *
     * @return the structured output strategy
     */
    public StructuredOutputStrategy getStructuredOutputStrategy() {
        return structuredOutputStrategy;
    }
}
