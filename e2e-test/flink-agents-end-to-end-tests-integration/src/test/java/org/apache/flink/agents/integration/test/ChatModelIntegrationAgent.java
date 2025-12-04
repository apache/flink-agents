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
import org.apache.flink.agents.api.chat.model.python.PythonChatModelConnection;
import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.integrations.chatmodels.azureai.AzureAIChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.azureai.AzureAIChatModelSetup;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;
import org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelSetup;

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
public class ChatModelIntegrationAgent extends Agent {
    public static final String OLLAMA_MODEL = "qwen3:0.6b";

    @ChatModelConnection
    public static ResourceDescriptor chatModelConnection() {
        String provider = System.getProperty("MODEL_PROVIDER", "OLLAMA");
        if (provider.equals("OLLAMA")) {
            return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                    .addInitialArgument("endpoint", "http://localhost:11434")
                    .addInitialArgument("requestTimeout", 240)
                    .build();
        } else if (provider.equals("AZURE")) {
            String endpoint = System.getenv().get("AZURE_ENDPOINT");
            String apiKey = System.getenv().get("AZURE_API_KEY");
            return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelConnection.class.getName())
                    .addInitialArgument("endpoint", endpoint)
                    .addInitialArgument("apiKey", apiKey)
                    .build();
        } else if (provider.equals("OPENAI")) {
            String apiKey = System.getenv().get("OPENAI_API_KEY");
            return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelConnection.class.getName())
                    .addInitialArgument("api_key", apiKey)
                    .build();
        } else if (provider.equals("PYTHON")) {
            return ResourceDescriptor.Builder.newBuilder(PythonChatModelConnection.class.getName())
                    .addInitialArgument(
                            "module", "flink_agents.integrations.chat_models.ollama_chat_model")
                    .addInitialArgument("clazz", "OllamaChatModelConnection")
                    .build();
        } else {
            throw new RuntimeException(String.format("Unknown model provider %s", provider));
        }
    }

    @ChatModelSetup
    public static ResourceDescriptor chatModel() {
        String provider = System.getProperty("MODEL_PROVIDER", "OLLAMA");

        if (provider.equals("OLLAMA")) {
            return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                    .addInitialArgument("connection", "chatModelConnection")
                    .addInitialArgument("model", OLLAMA_MODEL)
                    .addInitialArgument(
                            "tools",
                            List.of("calculateBMI", "convertTemperature", "createRandomNumber"))
                    .build();
        } else if (provider.equals("AZURE")) {
            return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelSetup.class.getName())
                    .addInitialArgument("connection", "chatModelConnection")
                    .addInitialArgument("model", "gpt-4o")
                    .addInitialArgument(
                            "tools",
                            List.of("calculateBMI", "convertTemperature", "createRandomNumber"))
                    .build();
        } else if (provider.equals("OPENAI")) {
            return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelSetup.class.getName())
                    .addInitialArgument("connection", "chatModelConnection")
                    .addInitialArgument("model", "gpt-4o-mini")
                    .addInitialArgument(
                            "tools",
                            List.of("calculateBMI", "convertTemperature", "createRandomNumber"))
                    .build();
        } else if (provider.equals("PYTHON")) {
            return ResourceDescriptor.Builder.newBuilder(PythonChatModelSetup.class.getName())
                    .addInitialArgument("connection", "chatModelConnection")
                    .addInitialArgument(
                            "module", "flink_agents.integrations.chat_models.ollama_chat_model")
                    .addInitialArgument("clazz", "OllamaChatModelSetup")
                    .addInitialArgument("model", OLLAMA_MODEL)
                    .addInitialArgument(
                            "tools",
                            List.of("calculateBMI", "convertTemperature", "createRandomNumber"))
                    .addInitialArgument("extract_reasoning", "true")
                    .build();
        } else {
            throw new RuntimeException(String.format("Unknown model provider %s", provider));
        }
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
        ctx.sendEvent(
                new ChatRequestEvent(
                        "chatModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    @Action(listenEvents = {ChatResponseEvent.class})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
