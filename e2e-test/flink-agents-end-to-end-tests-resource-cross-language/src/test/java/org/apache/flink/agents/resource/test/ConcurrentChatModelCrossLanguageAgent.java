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

package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Java agent exercising concurrent Java-to-Python-to-Java chat calls. */
public class ConcurrentChatModelCrossLanguageAgent extends Agent {

    /** Java connection that only returns after two chat requests overlap. */
    public static class OverlappingJavaChatModelConnection extends BaseChatModelConnection {
        private final CountDownLatch concurrentCalls = new CountDownLatch(2);

        public OverlappingJavaChatModelConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            concurrentCalls.countDown();
            try {
                if (!concurrentCalls.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting for concurrent cross-language chat request.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for concurrent cross-language chat request.", e);
            }

            ChatMessage request = messages.get(messages.size() - 1);
            return new ChatMessage(
                    MessageRole.ASSISTANT, "java-connection:" + request.getContent());
        }
    }

    @ChatModelConnection
    public static ResourceDescriptor overlappingJavaConnection() {
        return ResourceDescriptor.Builder.newBuilder(
                        OverlappingJavaChatModelConnection.class.getName())
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor pythonChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.PYTHON_WRAPPER_SETUP)
                .addInitialArgument("pythonClazz", ResourceName.ChatModel.Python.OLLAMA_SETUP)
                .addInitialArgument("connection", "overlappingJavaConnection")
                .addInitialArgument("model", "mock-model")
                .addInitialArgument("extract_reasoning", false)
                .build();
    }

    @Action(EventType.InputEvent)
    public static void requestChat(Event event, RunnerContext ctx) {
        String input = String.valueOf(InputEvent.fromEvent(event).getInput());
        ctx.sendEvent(
                new ChatRequestEvent(
                        "pythonChatModel", List.of(new ChatMessage(MessageRole.USER, input))));
    }

    @Action(EventType.ChatResponseEvent)
    public static void emitResponse(Event event, RunnerContext ctx) {
        ChatResponseEvent response = ChatResponseEvent.fromEvent(event);
        ctx.sendEvent(new OutputEvent(response.getResponse().getContent()));
    }
}
