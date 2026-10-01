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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

import java.util.List;

/**
 * Agent that receives a Base64 PNG as its input, asks an Ollama vision model about it, and outputs
 * the answer, so the image travels the whole agent path: input event, chat request event, the
 * built-in chat action and the Ollama connection.
 */
public class MultimodalChatAgent extends Agent {

    /** A small Ollama vision model; override with {@code OLLAMA_VISION_MODEL}. */
    public static final String VISION_MODEL = resolveVisionModel();

    private static String resolveVisionModel() {
        String model = System.getenv("OLLAMA_VISION_MODEL");
        return model == null || model.isBlank() ? "qwen3.5:2b" : model;
    }

    @ChatModelConnection
    public static ResourceDescriptor visionConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 240)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor visionModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "visionConnection")
                .addInitialArgument("model", VISION_MODEL)
                // Not every vision model supports thinking.
                .addInitialArgument("think", false)
                .build();
    }

    @Action(EventType.InputEvent)
    public static void describeImage(Event event, RunnerContext ctx) {
        String image = (String) InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(
                new ChatRequestEvent(
                        "visionModel",
                        List.of(
                                ChatMessage.user(
                                        List.of(
                                                TextBlock.of(
                                                        "What color is this image? Answer in one"
                                                                + " word."),
                                                ImageBlock.fromBase64("image/png", image))))));
    }

    @Action(EventType.ChatResponseEvent)
    public static void outputAnswer(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(ChatResponseEvent.fromEvent(event).getResponse().getText()));
    }
}
