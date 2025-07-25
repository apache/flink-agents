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
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.tools.BaseTool;
import org.apache.flink.agents.plan.tools.ToolParameters;
import org.apache.flink.agents.plan.tools.ToolResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class AgentWithResource extends Agent {
    public static class MockChatModel extends BaseChatModel {
        private final String endpoint;

        public MockChatModel(
                BiFunction<String, ResourceType, Resource> getResource,
                String endpoint,
                String promptName,
                List<String> toolNames) {
            super(getResource, promptName, toolNames);
            this.endpoint = endpoint;
        }

        @Override
        public ChatMessage chat(List<ChatMessage> messages) {
            Prompt prompt = (Prompt) getResource.apply(promptName, ResourceType.PROMPT);
            BaseTool tool = (BaseTool) getResource.apply(toolNames.get(0), ResourceType.TOOL);
            Map<String, Object> params = new HashMap<>();
            params.put("a", 1);
            params.put("b", 2);
            params.put("operation", "add");
            ToolParameters parameters = new ToolParameters(params);
            ToolResponse result = tool.call(parameters);
            String output =
                    String.format(
                            "Prompt: %s, input: %s, endpoint: %s, tool call result: %s",
                            prompt.formatString(new HashMap<>()),
                            messages.get(0).getContent(),
                            endpoint,
                            result.getResult());
            return new ChatMessage(MessageRole.ASSISTANT, output);
        }
    }

    @org.apache.flink.agents.api.annotation.Prompt
    public static Prompt myPrompt() {
        return new Prompt("This is a test prompt");
    }

    @ChatModel
    public static Map<String, Object> myChatModel() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ChatModel.CHAT_MODEL_CLASS_NAME, MockChatModel.class.getName());
        meta.put(
                ChatModel.CHAT_MODEL_ARGUMENTS,
                List.of("127.0.0.1", "myPrompt", List.of("calculate")));
        meta.put(
                ChatModel.CHAT_MODEL_ARGUMENTS_TYPES,
                List.of(String.class.getName(), String.class.getName(), List.class.getName()));
        return meta;
    }

    @Tool(description = "Performs basic arithmetic operations")
    public static double calculate(
            @ToolParam(name = "a") Double a,
            @ToolParam(name = "b") Double b,
            @ToolParam(name = "operation") String operation) {
        switch (operation.toLowerCase()) {
            case "add":
                return a + b;
            case "subtract":
                return a - b;
            case "multiply":
                return a * b;
            case "divide":
                if (b == 0) throw new IllegalArgumentException("Division by zero");
                return a / b;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    @Action(listenEvents = {InputEvent.class})
    public static void process(InputEvent event, RunnerContext ctx) throws Exception {
        BaseChatModel chatModel =
                (BaseChatModel) ctx.getResource("myChatModel", ResourceType.CHAT_MODEL);
        ChatMessage response =
                chatModel.chat(
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput())));
        ctx.sendEvent(new OutputEvent(response.getContent()));
    }
}
