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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlanDeclareChatModelTest {

    private AgentPlan agentPlan;

    public static class MockChatModel extends BaseChatModelSetup {
        public MockChatModel(
                ResourceDescriptor descriptor,
                BiFunction<String, ResourceType, Resource> getResource) {
            super(descriptor, getResource);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public ChatMessage chat(List<ChatMessage> messages) {
            // Return a deterministic response based on prompt name to assert on.
            return new ChatMessage(MessageRole.ASSISTANT, "ok:" + messages.get(0).getContent());
        }
    }

    static class ChatAgent extends Agent {
        @ChatModelSetup
        public static ResourceDescriptor testChatModel() {
            return ResourceDescriptor.Builder.newBuilder(MockChatModel.class.getName())
                    .addInitialArgument("endpoint", "127.0.0.1")
                    .addInitialArgument("topK", 5)
                    .addInitialArgument("topP", 0.2)
                    .addInitialArgument("prompt", "myPrompt")
                    .addInitialArgument("tools", List.of("calculate"))
                    .build();
        }

        @Action(listenEvents = {InputEvent.class})
        public void onInput(Event e, RunnerContext ctx) {
            // no-op for this test; validates action registration signature
        }
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new ChatAgent());
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
        BaseChatModelSetup model =
                (BaseChatModelSetup)
                        agentPlan.getResource("testChatModel", ResourceType.CHAT_MODEL);
        assertNotNull(model);

        Prompt prompt = new Prompt("Hello world");
        ChatMessage reply = model.chat(prompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertEquals(MessageRole.ASSISTANT, reply.getRole());
        assertEquals("ok:Hello world", reply.getContent());
    }

    @Test
    @DisplayName("AgentPlan JSON round trip keeps chat model usable")
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(agentPlan);
        AgentPlan restored = mapper.readValue(json, AgentPlan.class);

        BaseChatModelSetup model =
                (BaseChatModelSetup) restored.getResource("testChatModel", ResourceType.CHAT_MODEL);
        ChatMessage reply =
                model.chat(new Prompt("Hi").formatMessages(MessageRole.USER, new HashMap<>()));
        assertEquals("ok:Hi", reply.getContent());
    }

    @Test
    void testAddChatModel() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "testChatModel",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(MockChatModel.class.getName())
                        .addInitialArgument("endpoint", "127.0.0.1")
                        .addInitialArgument("topK", 5)
                        .addInitialArgument("topP", 0.2)
                        .addInitialArgument("prompt", "myPrompt")
                        .addInitialArgument("tools", List.of("calculate"))
                        .build());
        AgentPlan actualPlan = new AgentPlan(agent);
        BaseChatModelSetup actualChatModel =
                (BaseChatModelSetup)
                        actualPlan.getResource("testChatModel", ResourceType.CHAT_MODEL);
        BaseChatModelSetup expectedChatModel =
                (BaseChatModelSetup)
                        agentPlan.getResource("testChatModel", ResourceType.CHAT_MODEL);
        Assertions.assertEquals(expectedChatModel.getClass(), actualChatModel.getClass());
        Assertions.assertEquals(expectedChatModel.getConnection(), actualChatModel.getConnection());
        Assertions.assertEquals(expectedChatModel.getModel(), actualChatModel.getModel());
        Assertions.assertEquals(expectedChatModel.getPrompt(), actualChatModel.getPrompt());
        Assertions.assertEquals(expectedChatModel.getTools(), actualChatModel.getTools());
    }
}
