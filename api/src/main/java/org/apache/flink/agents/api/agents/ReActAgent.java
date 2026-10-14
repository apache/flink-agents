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

package org.apache.flink.agents.api.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.ClassUtils;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Built-in ReAct Agent implementation based on the function call ability of llm.
 *
 * <p>When an output schema is given, the agent adds a system instruction describing it. If the chat
 * model applies the schema natively, the instruction describes the schema's fields without
 * requiring JSON, and the schema is applied on the final conversion. Otherwise it asks for a JSON
 * response matching the schema.
 */
public class ReActAgent extends Agent {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private static final String DEFAULT_CHAT_MODEL = "_default_chat_model";
    private static final String DEFAULT_SCHEMA_PROMPT = "_default_schema_prompt";
    private static final String DEFAULT_NATIVE_SCHEMA_PROMPT = "_default_native_schema_prompt";
    private static final String DEFAULT_USER_PROMPT = "_default_user_prompt";

    // Kept byte-identical to the Python _NATIVE_SCHEMA_PROMPT_PREFIX.
    private static final String NATIVE_SCHEMA_PROMPT_PREFIX =
            "The final response must include the information for every required field of the"
                    + " following schema. It does not need to be in JSON format. Schema: ";

    private static final String SKILLS_ARGUMENT = "skills";
    private static final String SKILLS_SOURCES_ARGUMENT = "skills_sources";
    private static final String SKILLS_RESOURCE_NAME = "_react_agent_skills";

    /**
     * Action config flag recording that this agent registered {@link #DEFAULT_USER_PROMPT}, as
     * {@code output_schema} does for {@link #DEFAULT_SCHEMA_PROMPT}. A sub-agent's resource lookup
     * falls back to the root agent's resources, so {@link #startAction} looks up a built-in prompt
     * only when the running scope's own action config declares it; otherwise an unconfigured child
     * would pick up a parent ReActAgent's prompt under the same name.
     */
    private static final String HAS_USER_PROMPT = "has_user_prompt";

    private static final ObjectMapper mapper = new ObjectMapper();

    public ReActAgent(
            ResourceDescriptor descriptor, @Nullable Prompt prompt, @Nullable Object outputSchema) {
        this(descriptor, prompt, outputSchema, (List<String>) null);
    }

    /**
     * Creates a ReAct agent whose chat model may use the given skills.
     *
     * <p>The skills are combined with the {@code skills} argument of {@code descriptor}: the
     * descriptor's skills come first, followed by these, keeping only the first occurrence of each
     * name. A {@code null} or empty list adds nothing, so the descriptor is used exactly as given.
     * The descriptor itself is never modified.
     *
     * <p>Only skill names are combined. The {@code bash} tool's command policy, such as {@code
     * allowed_commands}, stays owned by the chat model descriptor and is left unchanged.
     *
     * @param descriptor The descriptor of the chat model used in this agent.
     * @param prompt Prompt to format the input into user messages.
     * @param outputSchema A {@link RowTypeInfo} or a Pojo class the response must match.
     * @param skills Names of the skills to expose to the chat model, each matching the {@code name}
     *     of a {@code SKILL.md} available to the agent.
     */
    public ReActAgent(
            ResourceDescriptor descriptor,
            @Nullable Prompt prompt,
            @Nullable Object outputSchema,
            @Nullable List<String> skills) {
        this.addResource(
                DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL, withSkills(descriptor, skills));
        Map<String, Object> actionConfig = new HashMap<>();

        if (outputSchema != null) {
            String jsonSchema;
            if (outputSchema instanceof RowTypeInfo) {
                jsonSchema = outputSchema.toString();
                outputSchema = new OutputSchema((RowTypeInfo) outputSchema);
            } else if (outputSchema instanceof Class) {
                Class<?> schemaClass = (Class<?>) outputSchema;
                try {
                    jsonSchema = mapper.generateJsonSchema(schemaClass).getSchemaNode().toString();
                } catch (JsonMappingException | IllegalArgumentException e) {
                    // Both are reachable: a class whose getters disagree on a property name fails
                    // the mapping, and one that would not serialize as a JSON object at all is
                    // refused by the generator with an IllegalArgumentException naming no remedy.
                    throw new IllegalArgumentException(
                            String.format(
                                    "Output schema %s cannot be rendered as a JSON Schema, so it"
                                            + " cannot constrain the response. Use a schema whose"
                                            + " fields are all JSON-Schema-renderable, or pass no"
                                            + " output schema. Rendering it reported: %s",
                                    schemaClass.getName(), e.getMessage()),
                            e);
                } catch (StackOverflowError e) {
                    // The generator carries no cycle guard, so a class that reaches itself
                    // through its own members recurses until the stack is gone. A separate clause
                    // rather than another type on the union above because the error carries no
                    // message to quote, so this case has to name the cause itself.
                    throw new IllegalArgumentException(
                            String.format(
                                    "Output schema %s is self-referential, so rendering it as a"
                                            + " JSON Schema does not terminate and it cannot"
                                            + " constrain the response. Use a schema that does not"
                                            + " refer back to itself, or pass no output schema.",
                                    schemaClass.getName()),
                            e);
                }
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Output schema %s is not supported. It must be a RowTypeInfo or"
                                        + " a Pojo class.",
                                outputSchema.getClass().getName()));
            }
            Prompt schemaPrompt =
                    Prompt.fromText(
                            String.format(
                                    "The final response should be json format, and match the schema %s",
                                    jsonSchema));
            this.addResource(DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT, schemaPrompt);
            this.addResource(
                    DEFAULT_NATIVE_SCHEMA_PROMPT,
                    ResourceType.PROMPT,
                    Prompt.fromText(NATIVE_SCHEMA_PROMPT_PREFIX + jsonSchema));
            actionConfig.put("output_schema", outputSchema);
        }

        if (prompt != null) {
            this.addResource(DEFAULT_USER_PROMPT, ResourceType.PROMPT, prompt);
            actionConfig.put(HAS_USER_PROMPT, true);
        }

        try {
            Method method =
                    this.getClass().getMethod("startAction", Event.class, RunnerContext.class);
            this.addAction(new String[] {InputEvent.EVENT_TYPE}, method, actionConfig);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Can't find the method stopAction, this must be a bug.");
        }
    }

    /**
     * Creates a ReAct agent whose chat model may use skills from the given sources.
     *
     * <p>This is the {@code Skills}-object form. The constructor registers the {@code Skills}
     * object as an agent-level resource so its sources are included in the merged {@code
     * _skills_config}, and records the source declarations as a marker in the chat model
     * descriptor. At runtime, the marker is expanded into skill names through {@code SkillManager}
     * and merged with any explicitly named skills on the descriptor.
     *
     * <p>The descriptor itself is never modified. The {@code bash} tool's command policy stays
     * owned by the chat model descriptor and is left unchanged.
     *
     * @param descriptor The descriptor of the chat model used in this agent.
     * @param prompt Prompt to format the input into user messages.
     * @param outputSchema A {@link RowTypeInfo} or a Pojo class the response must match.
     * @param skills Skill sources to expose to the chat model — all skills from these sources
     *     become available. A single {@code Skills} object may declare several sources, so it is
     *     not limited to one source. To combine sources declared in separate objects, gather them
     *     into one {@code Skills}, declare further {@code @Skills} methods, or register them on the
     *     agent with {@code addResource}. May be {@code null} or empty, in which case no source
     *     marker is recorded.
     */
    public ReActAgent(
            ResourceDescriptor descriptor,
            @Nullable Prompt prompt,
            @Nullable Object outputSchema,
            @Nullable Skills skills) {
        this(descriptor, prompt, outputSchema, (List<String>) null);
        if (skills != null && !skills.getSources().isEmpty()) {
            this.addResource(SKILLS_RESOURCE_NAME, ResourceType.SKILLS, skills);
            this.getResources()
                    .get(ResourceType.CHAT_MODEL)
                    .put(
                            DEFAULT_CHAT_MODEL,
                            withSkillsSources(
                                    (ResourceDescriptor)
                                            this.getResources()
                                                    .get(ResourceType.CHAT_MODEL)
                                                    .get(DEFAULT_CHAT_MODEL),
                                    skills));
        }
    }

    private static ResourceDescriptor withSkillsSources(
            ResourceDescriptor descriptor, Skills skills) {
        if (skills == null || skills.getSources().isEmpty()) {
            return descriptor;
        }
        Map<String, Object> arguments =
                descriptor.getInitialArguments() == null
                        ? new HashMap<>()
                        : new HashMap<>(descriptor.getInitialArguments());
        arguments.put(SKILLS_SOURCES_ARGUMENT, skills.getSources());
        // Rebuilt through the language dispatch so a Python chat model stays a Python declaration.
        return ResourceDescriptor.fromJson(
                descriptor.getLanguage(), descriptor.getModule(), descriptor.getClazz(), arguments);
    }

    private static ResourceDescriptor withSkills(
            ResourceDescriptor descriptor, @Nullable List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return descriptor;
        }
        Map<String, Object> arguments =
                descriptor.getInitialArguments() == null
                        ? new HashMap<>()
                        : new HashMap<>(descriptor.getInitialArguments());
        @SuppressWarnings("unchecked")
        List<String> declared = (List<String>) arguments.get(SKILLS_ARGUMENT);
        Set<String> merged = new LinkedHashSet<>(declared == null ? List.of() : declared);
        merged.addAll(skills);
        arguments.put(SKILLS_ARGUMENT, new ArrayList<>(merged));
        // Rebuilt through the language dispatch so a Python chat model stays a Python declaration.
        return ResourceDescriptor.fromJson(
                descriptor.getLanguage(), descriptor.getModule(), descriptor.getClazz(), arguments);
    }

    /**
     * Whether {@code input} is a map holding a single "input" entry - the one structured shape
     * {@link #startAction} converts without a user-supplied prompt, so a sub-agent declared with a
     * single "input" field needs no prompt. Keyed by equals, so a non-String key never matches.
     */
    private static boolean isSingleInputMap(Object input) {
        return input instanceof Map
                && ((Map<?, ?>) input).size() == 1
                && ((Map<?, ?>) input).containsKey("input");
    }

    public static void startAction(Event event, RunnerContext ctx) {
        // Built-in events reach an action as their concrete subclass (restored at the JSON
        // boundary), so the dispatched InputEvent is cast directly.
        InputEvent inputEvent = (InputEvent) event;
        Object input = inputEvent.getInput();

        Prompt userPrompt =
                Boolean.TRUE.equals(ctx.getActionConfigValue(HAS_USER_PROMPT))
                        ? declaredPrompt(ctx, DEFAULT_USER_PROMPT)
                        : null;

        List<ChatMessage> inputMessages = new ArrayList<>();
        // A String is not a primitive wrapper, but the documented input contract covers it like
        // one: without a prompt it becomes the user message, with a prompt it fills the {input}
        // placeholder.
        if (input instanceof String || ClassUtils.isPrimitiveOrWrapper(input.getClass())) {
            if (userPrompt != null) {
                inputMessages =
                        userPrompt.formatMessages(
                                MessageRole.USER, Map.of("input", String.valueOf(input)));
            } else {
                inputMessages.add(new ChatMessage(MessageRole.USER, String.valueOf(input)));
            }
        } else {
            if (userPrompt == null) {
                if (isSingleInputMap(input)) {
                    // The one structured shape that works without a user-supplied prompt: a
                    // single "input" entry, the arguments of a model-driven call against an
                    // input schema declaring only that field. The value becomes the user message
                    // directly, mirroring the prompt-less primitive branch.
                    inputMessages.add(
                            new ChatMessage(
                                    MessageRole.USER,
                                    String.valueOf(((Map<?, ?>) input).get("input"))));
                } else {
                    throw new RuntimeException(
                            String.format(
                                    "The input type is %s, which is not primitive types,"
                                            + " user should provide prompt to help convert it to"
                                            + " ChatMessage",
                                    input.getClass()));
                }
            } else {
                Map<String, String> fields = new HashMap<>();
                if (input instanceof Row) {
                    Row userInput = (Row) input;
                    for (String name : Objects.requireNonNull(userInput.getFieldNames(true))) {
                        fields.put(name, String.valueOf(userInput.getField(name)));
                    }
                } else if (input instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) input).entrySet()) {
                        fields.put(
                                String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                } else { // regard as pojo
                    ObjectMapper objectMapper = new ObjectMapper();
                    try {
                        // A read into the raw Map.class ignores the declared String values, so JSON
                        // numbers and booleans come back as Integer/Boolean. Stringify every value
                        // like the Map branch above, otherwise Prompt#format fails with a bare
                        // ClassCastException when it casts a placeholder value to String.
                        Map<?, ?> pojoFields =
                                mapper.readValue(objectMapper.writeValueAsString(input), Map.class);
                        for (Map.Entry<?, ?> entry : pojoFields.entrySet()) {
                            fields.put(
                                    String.valueOf(entry.getKey()),
                                    String.valueOf(entry.getValue()));
                        }
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(
                                String.format(
                                        "Input must be primitive type, Row, Map or Pojo, but is %s",
                                        input.getClass()));
                    }
                }

                inputMessages = userPrompt.formatMessages(MessageRole.USER, fields);
            }
        }

        Object outputSchema = ctx.getActionConfigValue("output_schema");

        if (outputSchema != null) {
            Prompt schemaPrompt = declaredPrompt(ctx, DEFAULT_SCHEMA_PROMPT);
            // A plan built before the native prompt existed has none, and then adds no
            // instruction on the native path.
            Prompt instruction =
                    appliesSchemaNatively(ctx, outputSchema)
                            ? promptOrNull(ctx, DEFAULT_NATIVE_SCHEMA_PROMPT)
                            : schemaPrompt;
            if (instruction != null) {
                List<ChatMessage> instruct =
                        instruction.formatMessages(MessageRole.SYSTEM, Map.of());
                int index = ChatMessage.findFirstSystemMessage(inputMessages);
                inputMessages.addAll(index + 1, instruct);
            }
        }

        ctx.sendEvent(new ChatRequestEvent(DEFAULT_CHAT_MODEL, inputMessages, outputSchema));
    }

    @Nullable
    private static Prompt promptOrNull(RunnerContext ctx, String name) {
        try {
            return (Prompt) ctx.getResource(name, ResourceType.PROMPT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the default chat model enforces the schema through the provider. If so, the loop is
     * told the schema's fields without being asked for JSON, and the schema is applied on the final
     * conversion. Any failure to answer, other than cancellation, keeps the JSON instruction: an
     * infeasible NATIVE strategy is reported by the chat model call itself.
     */
    private static boolean appliesSchemaNatively(RunnerContext ctx, Object outputSchema) {
        try {
            Resource chatModel = ctx.getResource(DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL);
            return chatModel instanceof BaseChatModelSetup
                    && ((BaseChatModelSetup) chatModel)
                            .willApplyNativeStructuredOutput(outputSchema);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug(
                    "Keeping the JSON schema instruction: native structured output is unresolved.",
                    e);
            return false;
        }
    }

    /**
     * Resolves a built-in prompt the running scope's action config declares. The constructor
     * registers the prompt together with its declaration, so a failed lookup is a broken plan and
     * is reported rather than silently dropping the prompt.
     */
    private static Prompt declaredPrompt(RunnerContext ctx, String name) {
        try {
            return (Prompt) ctx.getResource(name, ResourceType.PROMPT);
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format(
                            "Prompt %s is declared by this agent's action config but cannot be"
                                    + " resolved.",
                            name),
                    e);
        }
    }

    @Action(EventType.ChatResponseEvent)
    public static void stopAction(Event event, RunnerContext ctx) {
        ChatResponseEvent chatResponse = (ChatResponseEvent) event;
        ChatResult response = chatResponse.getResponse();

        Object output;
        if (chatResponse.getStructuredOutput() != null) {
            output = chatResponse.getStructuredOutput();
        } else {
            output = response.getText();
        }

        ctx.sendEvent(new OutputEvent(output));
    }
}
