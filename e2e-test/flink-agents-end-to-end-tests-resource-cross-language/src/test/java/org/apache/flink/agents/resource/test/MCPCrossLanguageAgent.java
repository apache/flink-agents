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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.MCPServer;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.junit.jupiter.api.Assertions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.resource.Constant.MCP_SERVER;

public class MCPCrossLanguageAgent extends Agent {
    @MCPServer(lang = "python")
    public static ResourceDescriptor pythonMCPServer() {
        return ResourceDescriptor.Builder.newBuilder(MCP_SERVER)
                .addInitialArgument("endpoint", "http://127.0.0.1:8000/mcp")
                .build();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void process(InputEvent event, RunnerContext ctx) throws Exception {
        Map<String, Object> testResult = new HashMap<>();
        try {
            Tool add = (Tool) ctx.getResource("add", ResourceType.TOOL);

            Assertions.assertTrue(add.getDescription().contains("Get the detailed information"));

            ToolResponse response = add.call(new ToolParameters(Map.of("a", 1, "b", 2)));
            Assertions.assertTrue(response.getResult().toString().contains("3"));
            System.out.println("[TEST] MCP Tools PASSED");

            Prompt askSum = (Prompt) ctx.getResource("ask_sum", ResourceType.PROMPT);
            List<ChatMessage> chatMessages =
                    askSum.formatMessages(MessageRole.USER, Map.of("a", "1", "b", "2"));
            Assertions.assertEquals(1, chatMessages.size());
            Assertions.assertEquals(
                    "Can you please calculate the sum of 1 and 2?",
                    chatMessages.get(0).getContent());
            Assertions.assertEquals(MessageRole.USER, chatMessages.get(0).getRole());

            String content = askSum.formatString(Map.of("a", "3", "b", "4"));
            Assertions.assertEquals("Can you please calculate the sum of 3 and 4?", content);
            System.out.println("[TEST] MCP Prompts PASSED");

            testResult.put("test_status", "PASSED");
            ctx.sendEvent(new OutputEvent(testResult));
        } catch (Exception e) {
            testResult.put("test_status", "FAILED");
            testResult.put("error", e.getMessage());
            ctx.sendEvent(new OutputEvent(testResult));
            System.err.printf("[TEST] MCP Cross Language test FAILED: %s%n", e.getMessage());
            throw e;
        }
    }
}
