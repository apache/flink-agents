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

package org.apache.flink.agents.integration.test.yaml;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;

import java.io.Serializable;
import java.util.Collections;

/**
 * Functions and POJOs referenced by the e2e YAML fixtures under {@code src/test/resources/yaml/}.
 * Each {@code function:} entry in the YAML points its dotted path at one of the static methods
 * below.
 */
public final class YamlChatActions {

    private YamlChatActions() {}

    /** Input record: a question routed to a chat model. */
    public static final class YamlChatInput implements Serializable {
        public int id;
        public String text;

        public YamlChatInput() {}

        public YamlChatInput(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** Output record: the chat model's textual answer, tagged with the original input id. */
    public static final class YamlChatOutput implements Serializable {
        public int id;
        public String answer;

        public YamlChatOutput() {}

        public YamlChatOutput(int id, String answer) {
            this.id = id;
            this.answer = answer;
        }
    }

    /**
     * Sum two integers. Referenced by the single-agent fixture as the {@code add} tool — the math
     * chat model is wired to call this when the user asks for a calculation.
     */
    @Tool(description = "Calculate the sum of two integers")
    public static int add(
            @ToolParam(name = "a", description = "The first operand") Integer a,
            @ToolParam(name = "b", description = "The second operand") Integer b) {
        return a + b;
    }

    /**
     * Query an order in the injected tenant. Referenced by the YAML parameter-injection fixture;
     * the {@code tenant_id} parameter is declared as injected in YAML, not on this method.
     */
    @Tool(description = "Query order in the current tenant")
    public static String queryOrder(
            @ToolParam(name = "order_id", description = "The order id") String orderId,
            @ToolParam(name = "tenant_id", description = "The tenant id") String tenantId) {
        return "yaml-checked:" + tenantId + ":" + orderId;
    }

    /**
     * Route the incoming text to the math or creative chat model. The math model has access to the
     * {@code add} tool; the creative model does not. Routing is a simple keyword check on the
     * input. Stash the record's {@code id} in short-term memory so {@code processChatResponse} can
     * re-attach it to the output.
     */
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        YamlChatInput data = (YamlChatInput) InputEvent.fromEvent(event).getInput();
        ctx.getShortTermMemory().set("input_id", data.id);
        String lower = data.text.toLowerCase();
        String modelName =
                (lower.contains("calculate") || lower.contains("sum"))
                        ? "math_chat_model"
                        : "creative_chat_model";
        ctx.sendEvent(
                new ChatRequestEvent(
                        modelName,
                        Collections.singletonList(new ChatMessage(MessageRole.USER, data.text)),
                        null));
    }

    /**
     * Forward the input text to the agent-local {@code chat_model}. Used by the multi-agent fixture
     * where each agent declares its own {@code chat_model} and the action simply pushes the user
     * message through.
     */
    public static void chatRequest(Event event, RunnerContext ctx) throws Exception {
        YamlChatInput data = (YamlChatInput) InputEvent.fromEvent(event).getInput();
        ctx.getShortTermMemory().set("input_id", data.id);
        ctx.sendEvent(
                new ChatRequestEvent(
                        "chat_model",
                        Collections.singletonList(new ChatMessage(MessageRole.USER, data.text)),
                        null));
    }

    /** Emit the model's text response, tagged with the original input id. */
    public static void processChatResponse(Event event, RunnerContext ctx) throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        ChatMessage response = chatResponse.getResponse();
        if (response == null || response.getContent() == null) {
            return;
        }
        Integer inputId = (Integer) ctx.getShortTermMemory().get("input_id").getValue();
        ctx.sendEvent(new OutputEvent(new YamlChatOutput(inputId, response.getContent())));
    }

    /**
     * Stage-2 action for the chained fixture: feed the upstream answer to a second chat model.
     *
     * <p>The upstream record is a {@link YamlChatOutput} produced by the math agent. Prompt the
     * model to restate the same numeric answer — the test only needs the chain to actually pass
     * through stage 2 (verifiable by the math digit surviving the second LLM hop). Stash the id in
     * short-term memory so the shared {@code processChatResponse} action can re-attach it.
     */
    public static void commentaryRequest(Event event, RunnerContext ctx) throws Exception {
        YamlChatOutput data = (YamlChatOutput) InputEvent.fromEvent(event).getInput();
        ctx.getShortTermMemory().set("input_id", data.id);
        String prompt =
                "Here is a math answer from another assistant: '"
                        + data.answer
                        + "'. Reply with the numeric result only.";
        ctx.sendEvent(
                new ChatRequestEvent(
                        "chat_model",
                        Collections.singletonList(new ChatMessage(MessageRole.USER, prompt)),
                        null));
    }
}
