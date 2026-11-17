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

package org.apache.flink.agents.api.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.ReActLoopBudget;
import org.apache.flink.agents.api.chat.messages.ChatMessage;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event representing a request for chat. */
public class ChatRequestEvent extends Event {

    public static final String EVENT_TYPE = "_chat_request_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The fixed cross-language attribute schema for this event: {@code model} and {@code messages}
     * are required, {@code prompt_args}, {@code output_schema}, {@code user_context}, and {@code
     * budget} are optional, and no other attribute is allowed. {@link #fromEvent} enforces it at
     * the JSON boundary. {@code user_context} is application-defined data propagated to the
     * response; it is not part of the prompt.
     */
    private static final List<BuiltInAttribute> ATTRIBUTE_SCHEMA =
            List.of(
                    BuiltInAttribute.required("model", String.class),
                    BuiltInAttribute.requiredList(
                            "messages",
                            "a ChatMessage or its serialized map",
                            ChatMessage.class,
                            Map.class),
                    BuiltInAttribute.optional("prompt_args", Map.class),
                    BuiltInAttribute.optionalUntyped("output_schema"),
                    BuiltInAttribute.optional(UserDefinedContext.ATTRIBUTE, Map.class),
                    BuiltInAttribute.optionalUntyped("budget"));

    public ChatRequestEvent(
            String model,
            List<ChatMessage> messages,
            @Nullable Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            @Nullable Map<String, Object> userContext) {
        this(model, messages, promptArgs, outputSchema, userContext, null);
    }

    public ChatRequestEvent(
            String model,
            List<ChatMessage> messages,
            @Nullable Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            @Nullable Map<String, Object> userContext,
            @Nullable ReActLoopBudget budget) {
        super(EVENT_TYPE);
        if (budget != null) setAttr("budget", budget);
        setAttr("model", model);
        setAttr("messages", new ArrayList<>(messages));
        setAttr("prompt_args", promptArgs != null ? promptArgs : Collections.emptyMap());
        if (outputSchema != null) {
            setAttr("output_schema", outputSchema);
        }
        UserDefinedContext.set(this, userContext);
    }

    public ChatRequestEvent(
            String model,
            List<ChatMessage> messages,
            @Nullable Map<String, Object> promptArgs,
            @Nullable Object outputSchema) {
        this(model, messages, promptArgs, outputSchema, null);
    }

    public ChatRequestEvent(
            String model, List<ChatMessage> messages, @Nullable Object outputSchema) {
        this(model, messages, null, outputSchema);
    }

    public ChatRequestEvent(String model, List<ChatMessage> messages) {
        this(model, messages, null, null);
    }

    @JsonCreator
    public ChatRequestEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeAttributes(attributes));
    }

    /** Converts nested attributes back to their typed forms. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        List<?> rawMessages = (List<?>) attributes.get("messages");
        if (rawMessages != null) {
            List<ChatMessage> messages = new ArrayList<>();
            for (Object m : rawMessages) {
                if (m instanceof ChatMessage) {
                    messages.add((ChatMessage) m);
                } else if (m instanceof Map) {
                    messages.add(MAPPER.convertValue(m, ChatMessage.class));
                }
            }
            attributes.put("messages", messages);
        }
        Object budget = attributes.get("budget");
        if (budget instanceof Map) {
            attributes.put("budget", ReActLoopBudget.fromMap((Map<?, ?>) budget));
        } else if (budget != null && !(budget instanceof ReActLoopBudget)) {
            throw new IllegalArgumentException("budget must be a ReActLoopBudget or Map");
        }
        return attributes;
    }

    /**
     * Reconstructs a typed ChatRequestEvent from a base Event, deserializing nested types.
     *
     * <p>Enforces the fixed cross-language attribute schema: {@code model} and {@code messages} are
     * required, {@code prompt_args}, {@code output_schema}, {@code user_context}, and {@code
     * budget} are optional, no other attribute is allowed, {@code model} must be a string, and
     * every {@code messages} element must be a {@link ChatMessage} or its serialized map — an
     * element such as a bare number is rejected rather than silently dropped.
     *
     * @param event the base event containing chat request data in attributes
     * @return a typed ChatRequestEvent
     * @throws IllegalArgumentException if the event violates the attribute schema
     */
    public static ChatRequestEvent fromEvent(Event event) {
        validateAttributeSchema(EVENT_TYPE, event.getAttributes(), ATTRIBUTE_SCHEMA);
        return reconstructFrom(event, ChatRequestEvent::new);
    }

    @JsonIgnore
    @Nullable
    public ReActLoopBudget getBudget() {
        return (ReActLoopBudget) getAttr("budget");
    }

    @JsonIgnore
    public String getModel() {
        return (String) getAttr("model");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getMessages() {
        return (List<ChatMessage>) getAttr("messages");
    }

    @JsonIgnore
    @Nullable
    public Object getOutputSchema() {
        return getAttr("output_schema");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPromptArgs() {
        Map<String, Object> args = (Map<String, Object>) getAttr("prompt_args");
        return args != null ? args : Collections.emptyMap();
    }

    /**
     * Returns application-defined data to propagate to the matching {@link ChatResponseEvent}, or
     * {@code null} when this request omitted it. The map is not added to messages or prompt
     * arguments.
     */
    @JsonIgnore
    @Nullable
    public Map<String, Object> getUserContext() {
        return UserDefinedContext.get(this);
    }
}
