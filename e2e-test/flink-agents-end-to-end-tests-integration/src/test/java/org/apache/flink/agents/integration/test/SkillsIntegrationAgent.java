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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Prompt;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.skills.Skills;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Agent that exercises the agent-skills feature end-to-end. Mirrors the Python {@code
 * agent_skills_test.SkillTestAgent}: declares two skills (math-calculator, joke-generator) and a
 * system prompt that instructs the model to load the skill before answering.
 */
public class SkillsIntegrationAgent extends Agent {

    /** Same model name as the Python {@code agent_skills_test} (a dashscope-hosted Qwen). */
    public static final String MODEL = "qwen3.6-plus";

    /**
     * Same default endpoint as the Python test — overridden by the {@code ACTION_BASE_URL} CI env
     * var.
     */
    public static final String DEFAULT_BASE_URL = "https://coding.dashscope.aliyuncs.com/v1";

    @ChatModelConnection
    public static ResourceDescriptor openaiConnection() {
        String apiKey = System.getenv("ACTION_API_KEY");
        String baseUrl = System.getenv().getOrDefault("ACTION_BASE_URL", DEFAULT_BASE_URL);
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                .addInitialArgument("api_key", apiKey)
                .addInitialArgument("api_base_url", baseUrl)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor openaiSetup() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                .addInitialArgument("connection", "openaiConnection")
                .addInitialArgument("model", MODEL)
                .addInitialArgument("skills", List.of("math-calculator", "joke-generator"))
                .addInitialArgument("allowed_commands", List.of("echo", "bc", "python", "python3"))
                .addInitialArgument("prompt", "systemPrompt")
                .build();
    }

    /** Resolve the {@code skills/} test resource directory to an absolute filesystem path. */
    @org.apache.flink.agents.api.annotation.Skills
    public static Skills mySkills() {
        URL url =
                Objects.requireNonNull(
                        SkillsIntegrationAgent.class.getClassLoader().getResource("skills"),
                        "skills/ test resources are missing");
        Path path = Paths.get(url.getPath());
        return Skills.fromLocalDir(path.toString());
    }

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt systemPrompt() {
        return org.apache.flink.agents.api.prompt.Prompt.fromMessages(
                Collections.singletonList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "You are a helpful assistant. Use the math-calculator skill when "
                                        + "asked to evaluate an expression, and the joke-generator "
                                        + "skill when asked for a joke. You must load the skill "
                                        + "first and strictly follow the instructions of the skill.")));
    }

    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void process(InputEvent event, RunnerContext ctx) throws Exception {
        ctx.sendEvent(
                new ChatRequestEvent(
                        "openaiSetup",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
