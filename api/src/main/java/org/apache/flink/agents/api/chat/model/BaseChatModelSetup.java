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

import org.apache.flink.agents.api.agents.OutputSchema;
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

    /**
     * Whether {@code outputSchema} should travel through the provider's native structured output on
     * a call issued through {@link #chatStructured(List, Map, Object)}, rather than be described to
     * the model in the prompt.
     *
     * <p>Framework-facing: public because the caller lives in another package. A user configures
     * the outcome through the {@link StructuredOutputStrategy} instead of calling this.
     *
     * <p>The connection is asked about the request {@link #chatStructured(List, Map, Object)}
     * sends: this schema, no tools, and the parameters {@link #getParameters()} returns, resolved
     * once so that the answer and the request concern the same parameters. Per-call parameters
     * passed to {@link #chatStructured(List, Map, Object)} are not seen here, so a caller that adds
     * parameters affecting feasibility must not rely on this answer.
     *
     * <p>A true answer is not a promise that the call succeeds: a connection may still raise once
     * its native branch applies the schema, for example on a conflicting caller-supplied response
     * format.
     *
     * <p>A setup with no bound connection, such as one that overrides {@link #open()} and {@link
     * #chat(List, Map, Map)} to answer by itself, answers false unless the strategy is {@link
     * StructuredOutputStrategy#NATIVE}.
     *
     * @param outputSchema the schema the call would carry, or null for an unconstrained call
     * @return true if the schema should be applied natively; false for a null schema or when no
     *     connection is bound
     * @throws IllegalArgumentException if the strategy is {@link StructuredOutputStrategy#NATIVE}
     *     and no connection is bound, or the connection cannot apply this schema to such a request
     */
    public boolean willApplyNativeStructuredOutput(@Nullable Object outputSchema) {
        if (outputSchema == null) {
            return false;
        }
        if (connection == null) {
            if (structuredOutputStrategy == StructuredOutputStrategy.NATIVE) {
                throw new IllegalArgumentException(
                        String.format(
                                "Structured output strategy NATIVE was requested, but %s has no"
                                        + " connection to apply the output schema natively.",
                                getClass().getName()));
            }
            return false;
        }
        NativeStructuredOutputSupport support =
                connection.supportsNativeStructuredOutput(outputSchema, List.of(), getParameters());
        if (support == NativeStructuredOutputSupport.INFEASIBLE
                && structuredOutputStrategy == StructuredOutputStrategy.NATIVE) {
            throw new IllegalArgumentException(
                    String.format(
                            "Structured output strategy NATIVE was requested, but %s cannot apply"
                                    + " the output schema %s natively. Use AUTO or PROMPT to"
                                    + " describe the schema in the prompt instead, or supply a"
                                    + " schema this connection can translate.",
                            connection.getClass().getName(), describeSchema(outputSchema)));
        }
        return structuredOutputStrategy.resolvesToNative(support);
    }

    private static String describeSchema(Object outputSchema) {
        if (outputSchema instanceof Class) {
            return ((Class<?>) outputSchema).getName();
        }
        if (outputSchema instanceof OutputSchema) {
            // The wrapper's toString names no schema.
            return String.valueOf(((OutputSchema) outputSchema).getSchema());
        }
        return outputSchema.getClass().getName();
    }

    /**
     * Sends one schema-carrying request to the connection, for a caller that has decided through
     * {@link #willApplyNativeStructuredOutput(Object)} that the schema travels natively.
     *
     * <p>Framework-facing: public because the caller lives in another package. A user reaches a
     * model through {@link #chat(List, Map, Map)}.
     *
     * <p>The messages are sent as given, without the bound prompt or the skill-discovery message,
     * because messages that already passed through {@link #chat(List, Map, Map)} would otherwise
     * carry them twice. No tools are bound, because a provider may drop a native schema from a
     * request that also binds tools.
     *
     * <p>Because no tools are bound, tool traffic is removed from what is sent: some providers
     * reject tool calls and tool results in a request that defines no tools. Tool-role messages and
     * assistant messages carrying tool calls are dropped whole, since keeping a tool-calling turn's
     * text would leave two assistant turns in a row. Turns still alternate only when an assistant
     * message without tool calls follows the tool traffic, which a caller guarantees by appending
     * the final answer. The caller's list and messages are not modified.
     *
     * @param messages the conversation to send, used as given apart from its tool traffic
     * @param modelParams parameters for this call, merged over {@link #getParameters()} the same
     *     way {@link #chat(List, Map, Map)} merges them, may be null
     * @param outputSchema the schema the call carries, must not be null
     * @return the connection's response
     * @throws NullPointerException if {@code outputSchema} is null, or if {@link #open()} has not
     *     bound the connection yet
     */
    public ChatMessage chatStructured(
            List<ChatMessage> messages,
            @Nullable Map<String, Object> modelParams,
            Object outputSchema) {
        Preconditions.checkNotNull(
                connection,
                "Connection is not initialized. Ensure open() is called before chatStructured().");
        Preconditions.checkNotNull(
                outputSchema,
                "chatStructured() requires an output schema. Call chat(List, Map, Map) for an"
                        + " unconstrained request.");

        Map<String, Object> params = this.getParameters();
        if (modelParams != null) {
            params.putAll(modelParams);
        }
        return connection.chat(withoutToolTraffic(messages), List.of(), params, outputSchema);
    }

    private static List<ChatMessage> withoutToolTraffic(List<ChatMessage> messages) {
        List<ChatMessage> sent = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            boolean toolCall =
                    message.getRole() == MessageRole.ASSISTANT
                            && message.getToolCalls() != null
                            && !message.getToolCalls().isEmpty();
            if (message.getRole() != MessageRole.TOOL && !toolCall) {
                sent.add(message);
            }
        }
        return sent;
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
     * StructuredOutputStrategy#AUTO}. {@link
     * StructuredOutputStrategy#resolvesToNative(NativeStructuredOutputSupport)} combines this
     * policy with the connection's support for the request.
     *
     * @return the structured output strategy
     */
    public StructuredOutputStrategy getStructuredOutputStrategy() {
        return structuredOutputStrategy;
    }
}
