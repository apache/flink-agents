/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModel;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModel;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatModelAgentPlanTest {

    private AgentPlan agentPlan;

    static class MockChatModel extends BaseChatModel {
        @Override
        public ChatMessage chat(Prompt request) {
            // Return a deterministic response based on prompt name to assert on.
            return new ChatMessage(MessageRole.ASSISTANT, "ok:" + request.getName());
        }

        @Override
        public String getName() {
            return "mockChatModel";
        }
    }

    static class ChatAgent extends Agent {
        @ChatModel(name = "testChatModel")
        private final BaseChatModel model;

        ChatAgent(BaseChatModel model) {
            this.model = model;
        }

        @Action(listenEvents = {InputEvent.class})
        public void onInput(Event e, RunnerContext ctx) {
            // no-op for this test; validates action registration signature
        }
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new ChatAgent(new MockChatModel()));
    }

    @Test
    @DisplayName("Discover @ChatModel in AgentPlan resource providers")
    void discoverChatModel() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.CHAT_MODEL));
        Map<String, ResourceProvider> cm = providers.get(ResourceType.CHAT_MODEL);
        assertTrue(cm.containsKey("testChatModel"));
    }

    @Test
    @DisplayName("Retrieve chat model and invoke chat(Prompt)")
    void retrieveAndChat() throws Exception {
        BaseChatModel model =
                (BaseChatModel) agentPlan.getResource("testChatModel", ResourceType.CHAT_MODEL);
        assertNotNull(model);

        Prompt prompt = new Prompt("testPrompt", "Hello world");
        ChatMessage reply = model.chat(prompt);

        assertEquals(MessageRole.ASSISTANT, reply.getRole());
        assertEquals("ok:testPrompt", reply.getContent());
    }

    @Test
    @DisplayName("AgentPlan JSON roundtrip keeps chat model usable")
    void jsonRoundtrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(agentPlan);
        AgentPlan restored = mapper.readValue(json, AgentPlan.class);

        BaseChatModel model =
                (BaseChatModel) restored.getResource("testChatModel", ResourceType.CHAT_MODEL);
        ChatMessage reply = model.chat(new Prompt("afterRoundtrip", "Hi"));
        assertEquals("ok:afterRoundtrip", reply.getContent());
    }
}
