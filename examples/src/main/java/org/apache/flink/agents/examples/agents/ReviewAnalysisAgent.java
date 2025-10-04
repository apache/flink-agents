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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Prompt;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.examples.agents.CustomTypesAndResources.REVIEW_ANALYSIS_PROMPT;

/**
 * An agent that uses a large language model (LLM) to analyze product reviews and generate a
 * satisfaction score and potential reasons for dissatisfaction.
 *
 * <p>This agent receives a product review and produces a satisfaction score and a list of reasons
 * for dissatisfaction. It handles prompt construction, LLM interaction, and output parsing.
 */
public class ReviewAnalysisAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt reviewAnalysisPrompt() {
        return REVIEW_ANALYSIS_PROMPT;
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewAnalysisModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("prompt", "reviewAnalysisPrompt")
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager"))
                .addInitialArgument("extract_reasoning", "true")
                .build();
    }

    /**
     * Tool for notifying the shipping manager when product received a negative review due to
     * shipping damage.
     *
     * @param id The id of the product that received a negative review due to shipping damage
     * @param review The negative review content
     */
    @Tool(
            description =
                    "Notify the shipping manager when product received a negative review due to shipping damage.")
    public static void notifyShippingManager(
            @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
        CustomTypesAndResources.notifyShippingManager(id, review);
    }

    /** Process input event and send chat request for review analysis. */
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        String input = (String) event.getInput();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CustomTypesAndResources.ProductReview inputObj =
                MAPPER.readValue(input, CustomTypesAndResources.ProductReview.class);

        ctx.getShortTermMemory().set("id", inputObj.getId());

        String content =
                String.format(
                        "{\n" + "\"id\": %s,\n" + "\"review\": \"%s\"\n" + "}",
                        inputObj.getId(), inputObj.getReview());
        ChatMessage msg = new ChatMessage(MessageRole.USER, "", Map.of("input", content));

        ctx.sendEvent(new ChatRequestEvent("reviewAnalysisModel", List.of(msg)));
    }

    @Action(listenEvents = ChatResponseEvent.class)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx)
            throws Exception {
        JsonNode jsonNode = MAPPER.readTree(event.getResponse().getContent());
        JsonNode scoreNode = jsonNode.findValue("score");
        JsonNode reasonsNode = jsonNode.findValue("reasons");
        if (scoreNode == null || reasonsNode == null) {
            throw new IllegalStateException(
                    "Invalid response from LLM: missing 'score' or 'reasons' field.");
        }
        List<String> result = new ArrayList<>();
        if (reasonsNode.isArray()) {
            for (JsonNode node : reasonsNode) {
                result.add(node.asText());
            }
        }

        ctx.sendEvent(
                new OutputEvent(
                        new CustomTypesAndResources.ProductReviewAnalysisRes(
                                ctx.getShortTermMemory().get("id").getValue().toString(),
                                scoreNode.asInt(),
                                result)));
    }
}
