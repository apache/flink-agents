package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.*;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.integrations.chatmodels.azureai.AzureAIChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.azureai.AzureAIChatModelSetup;

import java.util.Collections;
import java.util.List;

public class AgentWithAzureAI extends Agent {

    private static final String AZURE_ENDPOINT = "";
    private static final String AZURE_API_KEY = "";

    @ChatModelConnection
    public static ResourceDescriptor azureAIChatModelConnection() {
        return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelConnection.class.getName())
                .addInitialArgument("endpoint", AZURE_ENDPOINT)
                .addInitialArgument("apiKey", AZURE_API_KEY)
                .build();
    }

    private static boolean callingRealMode() {
        if (AZURE_ENDPOINT != null
                && !AZURE_ENDPOINT.isEmpty()
                && AZURE_API_KEY != null
                && !AZURE_API_KEY.isEmpty()) {
            return true;
        } else {
            return false;
        }
    }

    @ChatModelSetup
    public static ResourceDescriptor azureAIChatModel() {
        ResourceDescriptor.Builder builder;
        if (callingRealMode()) {
            System.out.println(
                    "Calling real Azure AI service. Make sure the endpoint and apiKey are correct.");
            builder =
                    ResourceDescriptor.Builder.newBuilder(AzureAIChatModelSetup.class.getName())
                            .addInitialArgument("connection", "azureAIChatModelConnection")
                            .addInitialArgument("model", "gpt-4o-mini");
        } else {
            System.out.println("Using mock Azure AI chat model for testing.");
            // leverage the mock chat model for testing
            builder =
                    ResourceDescriptor.Builder.newBuilder(
                            AgentWithResource.MockChatModel.class.getName());
        }
        return builder
                // register the available tools
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
                (BaseChatModelSetup) ctx.getResource("azureAIChatModel", ResourceType.CHAT_MODEL);
        ChatMessage response =
                chatModel.chat(
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput())));
        ctx.sendEvent(new OutputEvent(response.getContent()));
    }
}
