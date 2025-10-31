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

package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;

import java.util.Collections;
import java.util.List;

/**
 * Agent example that integrates an external Ollama chat model into Flink Agents.
 *
 * <p>This class demonstrates how to:
 *
 * <ul>
 *   <li>Declare a chat model connection using {@link ChatModelConnection} metadata pointing to
 *       {@link OllamaChatModelConnection}
 *   <li>Declare a chat model setup using {@link ChatModelSetup} metadata pointing to {@link
 *       OllamaChatModelSetup}
 *   <li>Expose callable tools via {@link Tool} annotated static methods (temperature conversion,
 *       BMI, random number)
 *   <li>Fetch a chat model from the {@link RunnerContext} and perform a single-turn chat
 *   <li>Emit the model response as an {@link OutputEvent}
 * </ul>
 *
 * <p>The {@code ollamaChatModel()} method publishes a resource with type {@link
 * ResourceType#CHAT_MODEL} so it can be retrieved at runtime inside the {@code process} action. The
 * resource is configured with the connection name, the model name and the list of tool names that
 * the model is allowed to call.
 */
public class AgentWithOllama extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor ollamaChatModelConnection() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor ollamaChatModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument(
                        "tools",
                        List.of("calculateBMI", "convertTemperature", "createRandomNumber"))
                .build();
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
        BaseChatModelSetup chatModel =
                (BaseChatModelSetup) ctx.getResource("ollamaChatModel", ResourceType.CHAT_MODEL);
        ctx.sendEvent(
                new ChatRequestEvent(
                        "ollamaChatModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    @Action(listenEvents = {ChatResponseEvent.class})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
