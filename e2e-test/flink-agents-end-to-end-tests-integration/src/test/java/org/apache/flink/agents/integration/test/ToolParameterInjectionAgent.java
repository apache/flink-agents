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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.api.java.functions.KeySelector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** E2E agent covering tool parameter injection in a real Flink job. */
public class ToolParameterInjectionAgent extends Agent {

    /** Key selector for order ids. */
    public static class OrderKeySelector implements KeySelector<String, String> {
        @Override
        public String getKey(String value) {
            return value;
        }
    }

    /** Mock chat connection that emits one queryOrder tool call, then echoes the tool result. */
    public static class MockToolChatConnection extends BaseChatModelConnection {
        public MockToolChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                List<org.apache.flink.agents.api.tools.Tool> tools,
                Map<String, Object> modelParams) {
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.getRole() == MessageRole.TOOL) {
                return new ChatMessage(MessageRole.ASSISTANT, lastMessage.getContent());
            }

            for (org.apache.flink.agents.api.tools.Tool tool : tools) {
                String inputSchema = tool.getMetadata().getInputSchema();
                if (inputSchema.contains("tenant_id")) {
                    throw new IllegalStateException(
                            "Injected argument leaked into tool schema: " + inputSchema);
                }
            }
            String orderId = lastMessage.getContent();
            return new ChatMessage(
                    MessageRole.ASSISTANT,
                    "",
                    List.of(
                            Map.of(
                                    "id",
                                    "call-" + orderId,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            "queryOrder",
                                            "arguments",
                                            Map.of("order_id", orderId)))));
        }
    }

    /** Mock chat model setup bound to queryOrder. */
    public static class MockToolChatModel extends BaseChatModelSetup {
        public MockToolChatModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    @ChatModelConnection
    public static ResourceDescriptor mockChatConnection() {
        return ResourceDescriptor.Builder.newBuilder(MockToolChatConnection.class.getName())
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor mockChatModel() {
        return ResourceDescriptor.Builder.newBuilder(MockToolChatModel.class.getName())
                .addInitialArgument("connection", "mockChatConnection")
                .addInitialArgument("model", "mock")
                .addInitialArgument("tools", List.of("queryOrder"))
                .build();
    }

    @Tool(description = "Query order in the current tenant.")
    public static String queryOrder(
            @ToolParam(name = "order_id") String orderId,
            @ToolParam(name = "tenant_id", injected = true, source = ToolParameterSource.CONFIG)
                    String tenantId) {
        return "checked:" + tenantId + ":" + orderId;
    }

    @Action(EventType.InputEvent)
    public static void requestTool(Event event, RunnerContext ctx) {
        String orderId = String.valueOf(InputEvent.fromEvent(event).getInput());
        ctx.sendEvent(
                new ChatRequestEvent(
                        "mockChatModel", List.of(new ChatMessage(MessageRole.USER, orderId))));
    }

    @Action(EventType.ChatResponseEvent)
    public static void emitResult(Event event, RunnerContext ctx) {
        ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(event);
        ctx.sendEvent(new OutputEvent(responseEvent.getResponse().getContent()));
    }
}
