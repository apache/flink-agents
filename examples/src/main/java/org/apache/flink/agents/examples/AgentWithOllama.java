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

package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModel;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModel;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent example that integrates an external Ollama chat model into Flink Agents.
 *
 * <p>This class demonstrates how to:
 *
 * <ul>
 *   <li>Declare a chat model resource using {@link ChatModel} metadata pointing to {@link
 *       OllamaChatModel}
 *   <li>Expose callable tools via {@link Tool} annotated static methods (temperature conversion,
 *       BMI, random number)
 *   <li>Fetch a chat model from the {@link RunnerContext} and perform a single-turn chat
 *   <li>Emit the model response as an {@link OutputEvent}
 * </ul>
 *
 * <p>The {@code ollamChatModel()} method publishes a resource with type {@link
 * ResourceType#CHAT_MODEL} so it can be retrieved at runtime inside the {@code process} action. The
 * resource is configured with the Ollama HTTP endpoint, the model name, a prompt resource name, and
 * the list of tool names that the model is allowed to call.
 */
public class AgentWithOllama extends Agent {

    @ChatModel
    public static Map<String, Object> ollamChatModel() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ChatModel.CHAT_MODEL_CLASS_NAME, OllamaChatModel.class.getName());
        meta.put(
                ChatModel.CHAT_MODEL_ARGUMENTS,
                List.of(
                        "http://localhost:11434",
                        "qwen3:4b",
                        "myPrompt",
                        List.of("calculateBMI", "convertTemperature", "createRandomNumber")));
        meta.put(
                ChatModel.CHAT_MODEL_ARGUMENTS_TYPES,
                List.of(
                        String.class.getName(),
                        String.class.getName(),
                        String.class.getName(),
                        List.class.getName()));
        return meta;
    }

    @Tool(description = "Converts temperature between Celsius and Fahrenheit")
    public static double convertTemperature(
            @ToolParam(name = "value", description = "Temperature value to convert") Double value,
            @ToolParam(
                            name = "fromUnit",
                            description = "Source unit ('C' for Celsius or 'F' for Fahrenheit)")
                    String fromUnit,
            @ToolParam(
                            name = "toUnit",
                            description = "Target unit ('C' for Celsius or 'F' for Fahrenheit)")
                    String toUnit) {

        fromUnit = fromUnit.toUpperCase();
        toUnit = toUnit.toUpperCase();

        if (fromUnit.equals(toUnit)) {
            return value;
        }

        if (fromUnit.equals("C") && toUnit.equals("F")) {
            return (value * 9 / 5) + 32;
        } else if (fromUnit.equals("F") && toUnit.equals("C")) {
            return (value - 32) * 5 / 9;
        } else {
            throw new IllegalArgumentException("Invalid temperature units. Use 'C' or 'F'");
        }
    }

    @Tool(description = "Calculates Body Mass Index (BMI)")
    public static double calculateBMI(
            @ToolParam(name = "weightKg", description = "Weight in kilograms") Double weightKg,
            @ToolParam(name = "heightM", description = "Height in meters") Double heightM) {

        if (weightKg <= 0 || heightM <= 0) {
            throw new IllegalArgumentException("Weight and height must be positive values");
        }
        return weightKg / (heightM * heightM);
    }

    @Tool(description = "Create a random number")
    public static double createRandomNumber() {
        return Math.random();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void process(InputEvent event, RunnerContext ctx) throws Exception {
        BaseChatModel chatModel =
                (BaseChatModel) ctx.getResource("ollamChatModel", ResourceType.CHAT_MODEL);
        ChatMessage response =
                chatModel.chat(
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput())));
        ctx.sendEvent(new OutputEvent(response.getContent()));
    }
}
