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
package org.apache.flink.agents.examples.agents;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
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

import java.util.Collections;
import java.util.List;

/**
 * An agent that answers arithmetic questions using the {@code math-calculator} skill.
 *
 * <p>Instead of relying on the LLM to compute by itself, this agent exposes a {@code
 * math-calculator} skill. When asked to evaluate an expression, the model loads the skill ({@code
 * load_skill}) and follows its instructions to compute the result with the {@code bc} calculator
 * through the built-in {@code bash} tool.
 */
public class MathAgent extends Agent {

    /**
     * Load skills from the {@code skills/} directory packaged on the classpath.
     *
     * <p>The {@code @Skills} annotation and the {@link Skills} resource share a simple name but
     * live in different packages, so the annotation is fully-qualified here.
     */
    @org.apache.flink.agents.api.annotation.Skills
    public static Skills mySkills() {
        return Skills.fromClasspath("skills");
    }

    /** System prompt instructing the model to use the skill. */
    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt systemPrompt() {
        return org.apache.flink.agents.api.prompt.Prompt.fromMessages(
                Collections.singletonList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "You are a helpful math assistant. Use the math-calculator skill "
                                        + "when asked to evaluate an expression. You must load the "
                                        + "skill first and strictly follow its instructions. Reply "
                                        + "with only the final numeric result.")));
    }

    /** ChatModel with the math-calculator skill enabled. */
    @ChatModelSetup
    public static ResourceDescriptor mathModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3.5:9b")
                .addInitialArgument("prompt", "systemPrompt")
                // Expose the declared skill to this model by name.
                .addInitialArgument("skills", List.of("math-calculator"))
                // Whitelist the shell commands the built-in bash tool may run.
                .addInitialArgument("allowed_commands", List.of("echo", "bc"))
                .build();
    }

    /** Process input event and send a chat request to evaluate the question. */
    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void processInput(InputEvent event, RunnerContext ctx) {
        ctx.sendEvent(
                new ChatRequestEvent(
                        "mathModel",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    /** Process chat response event and send the answer as output. */
    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
