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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.openai.OpenAIChatModelSetup;

import java.util.Collections;
import java.util.List;

/**
 * Agent example that integrates the OpenAI chat integration into Flink Agents.
 *
 * <p>Set the {@code OPENAI_API_KEY} environment variable (and optionally {@code
 * OPENAI_API_BASE_URL} for Azure-hosted endpoints) before running in real mode. When the key is
 * missing the agent can be used in the mock tests.
 */
public class AgentWithOpenAI extends Agent {

    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String OPENAI_API_BASE_URL = System.getenv("OPENAI_API_BASE_URL");

    public static boolean callingRealMode() {
        return OPENAI_API_KEY != null && !OPENAI_API_KEY.isEmpty();
    }

    @ChatModelConnection
    public static ResourceDescriptor openAIChatModelConnection() {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(OpenAIChatModelConnection.class.getName())
                        .addInitialArgument("api_key", OPENAI_API_KEY);

        if (OPENAI_API_BASE_URL != null && !OPENAI_API_BASE_URL.isEmpty()) {
            builder.addInitialArgument("api_base_url", OPENAI_API_BASE_URL);
        }

        return builder.build();
    }

    @ChatModelSetup
    public static ResourceDescriptor openAIChatModel() {
        if (callingRealMode()) {
            System.out.println(
                    "Calling real OpenAI service. Make sure the OPENAI_API_KEY (and optional "
                            + "OPENAI_API_BASE_URL) environment variables are set correctly.");
        } else {
            System.out.println("OPENAI_API_KEY not found – running AgentWithOpenAI in mock mode.");
        }

        return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelSetup.class.getName())
                .addInitialArgument("connection", "openAIChatModelConnection")
                .addInitialArgument("model", "gpt-4o-mini")
                .addInitialArgument("temperature", 0.2)
                .addInitialArgument("max_tokens", 50000)
                .addInitialArgument("strict", true)
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
        ctx.sendEvent(
                new ChatRequestEvent(
                        "openAIChatModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    @Action(listenEvents = {ChatResponseEvent.class})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
