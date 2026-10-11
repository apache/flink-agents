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
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentMetadataProvider;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Built-in ReAct Agent implementation based on the function call ability of llm. . */
public class ReActAgent extends Agent implements SubagentMetadataProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private static final String DEFAULT_CHAT_MODEL = "_default_chat_model";
    private static final String DEFAULT_SCHEMA_PROMPT = "_default_schema_prompt";
    private static final String DEFAULT_USER_PROMPT = "_default_user_prompt";
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Caller-facing description when running as a sub-agent, used unless overridden. */
    private static final String DEFAULT_SUBAGENT_DESCRIPTION =
            "A general-purpose agent that completes a delegated task using its tools.";

    /**
     * Caller-facing input schema when running as a sub-agent, used unless overridden. It names the
     * single "input" field the default prompt-free map path of {@link #startAction} passes through
     * as the user message, so a model-driven delegation call works out of the box.
     */
    private static final String DEFAULT_SUBAGENT_INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},"
                    + "\"required\":[\"input\"]}";

    @Nullable private final String subagentDescription;

    @Nullable private final String subagentInputSchema;

    public ReActAgent(
            ResourceDescriptor descriptor, @Nullable Prompt prompt, @Nullable Object outputSchema) {
        this(descriptor, prompt, outputSchema, null, null);
    }

    public ReActAgent(
            ResourceDescriptor descriptor,
            @Nullable Prompt prompt,
            @Nullable Object outputSchema,
            @Nullable String subagentDescription,
            @Nullable String subagentInputSchema) {
        this.subagentDescription = subagentDescription;
        this.subagentInputSchema = subagentInputSchema;
        this.addResource(DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL, descriptor);
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
            actionConfig.put("output_schema", outputSchema);
        }

        if (prompt != null) {
            this.addResource(DEFAULT_USER_PROMPT, ResourceType.PROMPT, prompt);
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

    @Override
    public String getSubagentDescription() {
        return subagentDescription != null ? subagentDescription : DEFAULT_SUBAGENT_DESCRIPTION;
    }

    @Override
    public String getSubagentInputSchema() {
        return subagentInputSchema != null ? subagentInputSchema : DEFAULT_SUBAGENT_INPUT_SCHEMA;
    }

    /**
     * Whether {@code input} is the single "input"-keyed map the default sub-agent input schema
     * declares - the one structured shape {@link #startAction} converts without a user-supplied
     * prompt. Keyed by equals, so a non-String key never matches.
     */
    private static boolean isDefaultSubagentInputMap(Object input) {
        return input instanceof Map
                && ((Map<?, ?>) input).size() == 1
                && ((Map<?, ?>) input).containsKey("input");
    }

    public static void startAction(Event event, RunnerContext ctx) {
        // Built-in events reach an action as their concrete subclass (restored at the JSON
        // boundary), so the dispatched InputEvent is cast directly.
        InputEvent inputEvent = (InputEvent) event;
        Object input = inputEvent.getInput();

        Prompt userPrompt;
        try {
            userPrompt = (Prompt) ctx.getResource(DEFAULT_USER_PROMPT, ResourceType.PROMPT);
        } catch (Exception e) {
            userPrompt = null;
        }

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
                if (isDefaultSubagentInputMap(input)) {
                    // The one structured shape that works without a user-supplied prompt: a
                    // single "input" entry, the shape the default sub-agent input schema
                    // declares. The value becomes the user message directly, mirroring the
                    // prompt-less primitive branch, so a model-driven delegation call works
                    // out of the box.
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

        Prompt schmaPrompt;
        try {
            schmaPrompt = (Prompt) ctx.getResource(DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT);
        } catch (Exception e) {
            schmaPrompt = null;
        }

        if (schmaPrompt != null) {
            List<ChatMessage> instruct = schmaPrompt.formatMessages(MessageRole.SYSTEM, Map.of());
            int index = ChatMessage.findFirstSystemMessage(inputMessages);
            inputMessages.addAll(index + 1, instruct);
        }

        Object outputSchema = ctx.getActionConfigValue("output_schema");

        ctx.sendEvent(new ChatRequestEvent(DEFAULT_CHAT_MODEL, inputMessages, outputSchema));
    }

    @Action(EventType.ChatResponseEvent)
    public static void stopAction(Event event, RunnerContext ctx) {
        // See startAction: the dispatched ChatResponseEvent is already the concrete subclass.
        ChatResponseEvent chatResponse = (ChatResponseEvent) event;
        ChatMessage response = chatResponse.getResponse();

        Object output;
        if (response.getExtraArgs().containsKey(STRUCTURED_OUTPUT)) {
            output = response.getExtraArgs().get(STRUCTURED_OUTPUT);
        } else {
            output = response.getText();
        }

        ctx.sendEvent(new OutputEvent(output));
    }
}
