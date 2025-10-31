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
import org.apache.flink.agents.api.annotation.*;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.chatmodels.azureai.AzureAIChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.azureai.AzureAIChatModelSetup;

import java.util.Collections;

public class AgentWithAzureAI extends Agent {

    private static final String AZURE_ENDPOINT = "";
    private static final String AZURE_API_KEY = "";

    public static boolean callingRealMode() {
        if (AZURE_ENDPOINT != null
                && !AZURE_ENDPOINT.isEmpty()
                && AZURE_API_KEY != null
                && !AZURE_API_KEY.isEmpty()) {
            return true;
        } else {
            return false;
        }
    }

    @ChatModelConnection
    public static ResourceDescriptor azureAIChatModelConnection() {
        return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelConnection.class.getName())
                .addInitialArgument("endpoint", AZURE_ENDPOINT)
                .addInitialArgument("apiKey", AZURE_API_KEY)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor azureAIChatModel() {
        System.out.println(
                "Calling real Azure AI service. Make sure the endpoint and apiKey are correct.");
        return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelSetup.class.getName())
                .addInitialArgument("connection", "azureAIChatModelConnection")
                .addInitialArgument("model", "gpt-4o")
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
                        "azureAIChatModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    @Action(listenEvents = {ChatResponseEvent.class})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
