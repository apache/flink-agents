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

import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

import java.util.Collections;

/**
 * Minimal agent for token metrics e2e testing. Uses OpenAI completions connection pointing to a
 * MockWebServer endpoint. Set {@link #apiBaseUrl} before building the agent plan.
 */
public class TokenMetricsE2EAgent extends Agent {

    static String apiBaseUrl;

    @ChatModelConnection
    public static ResourceDescriptor chatModelConnection() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                .addInitialArgument("api_key", "test-key")
                .addInitialArgument("api_base_url", apiBaseUrl)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor chatModel() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                .addInitialArgument("connection", "chatModelConnection")
                .addInitialArgument("model", "gpt-4o-mini")
                .build();
    }

    @Action(EventType.InputEvent)
    public static void process(InputEvent event, RunnerContext ctx) throws Exception {
        ctx.sendEvent(
                new ChatRequestEvent(
                        "chatModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    @Action(EventType.ChatResponseEvent)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
