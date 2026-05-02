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
public class ReActAgent extends Agent {
    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private static final String DEFAULT_CHAT_MODEL = "_default_chat_model";
    private static final String DEFAULT_SCHEMA_PROMPT = "_default_schema_prompt";
    private static final String DEFAULT_USER_PROMPT = "_default_user_prompt";
    private static final ObjectMapper mapper = new ObjectMapper();

    public ReActAgent(
            ResourceDescriptor descriptor, @Nullable Prompt prompt, @Nullable Object outputSchema) {
        this.addResource(DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL, descriptor);

        if (outputSchema != null) {
            String jsonSchema;
            if (outputSchema instanceof RowTypeInfo) {
                jsonSchema = outputSchema.toString();
                outputSchema = new OutputSchema((RowTypeInfo) outputSchema);
            } else if (outputSchema instanceof Class) {
                try {
                    jsonSchema = mapper.generateJsonSchema((Class<?>) outputSchema).toString();
                } catch (JsonMappingException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new IllegalArgumentException(
                        "Output schema must be RowTypeInfo or Pojo class.");
            }
            Prompt schemaPrompt =
                    Prompt.fromText(
                            String.format(
                                    "The final response should be json format, and match the schema %s",
                                    jsonSchema));
            this.addResource(DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT, schemaPrompt);
        }

        if (prompt != null) {
            this.addResource(DEFAULT_USER_PROMPT, ResourceType.PROMPT, prompt);
        }

        Map<String, Object> actionConfig = new HashMap<>();
        actionConfig.put("output_schema", outputSchema);

        try {
            Method method =
                    this.getClass().getMethod("startAction", InputEvent.class, RunnerContext.class);
            this.addAction(new String[] {InputEvent.EVENT_TYPE}, method, actionConfig);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Can't find the method stopAction, this must be a bug.");
        }
    }

    public static void startAction(InputEvent event, RunnerContext ctx) {
        Object input = event.getInput();

        Prompt userPrompt;
        try {
            userPrompt = (Prompt) ctx.getResource(DEFAULT_USER_PROMPT, ResourceType.PROMPT);
        } catch (Exception e) {
            userPrompt = null;
        }

        List<ChatMessage> inputMessages = new ArrayList<>();
        if (ClassUtils.isPrimitiveOrWrapper(input.getClass())) {
            if (userPrompt != null) {
                inputMessages =
                        userPrompt.formatMessages(
                                MessageRole.USER, Map.of("input", String.valueOf(input)));
            } else {
                inputMessages.add(new ChatMessage(MessageRole.USER, String.valueOf(input)));
            }
        } else {
            if (userPrompt == null) {
                throw new RuntimeException(
                        String.format(
                                "The input type is %s, which is not primitive types,"
                                        + " user should provide prompt to help convert it to ChatMessage",
                                input.getClass()));
            }

            Map<String, String> fields = new HashMap<>();
            if (input instanceof Row) {
                Row userInput = (Row) input;
                for (String name : Objects.requireNonNull(userInput.getFieldNames(true))) {
                    fields.put(name, String.valueOf(userInput.getField(name)));
                }
            } else { // regard as pojo
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    fields = mapper.readValue(objectMapper.writeValueAsString(input), Map.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(
                            String.format(
                                    "Input must be primitive type, Row or Pojo, but is %s",
                                    input.getClass()));
                }
            }

            inputMessages = userPrompt.formatMessages(MessageRole.USER, fields);
        }

        Prompt schmaPrompt;
        try {
            schmaPrompt = (Prompt) ctx.getResource(DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT);
        } catch (Exception e) {
            schmaPrompt = null;
        }

        if (schmaPrompt != null) {
            List<ChatMessage> instruct = schmaPrompt.formatMessages(MessageRole.SYSTEM, Map.of());
            inputMessages.addAll(0, instruct);
        }

        Object outputSchema = ctx.getActionConfigValue("output_schema");

        ctx.sendEvent(new ChatRequestEvent(DEFAULT_CHAT_MODEL, inputMessages, outputSchema));
    }

    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void stopAction(ChatResponseEvent event, RunnerContext ctx) {
        ChatMessage response = event.getResponse();

        Object output;
        if (response.getExtraArgs().containsKey(STRUCTURED_OUTPUT)) {
            output = response.getExtraArgs().get(STRUCTURED_OUTPUT);
        } else {
            output = String.valueOf(response.getContent());
        }

        ctx.sendEvent(new OutputEvent(output));
    }
}
