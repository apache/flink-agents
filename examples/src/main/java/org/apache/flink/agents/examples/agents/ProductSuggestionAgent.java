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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Prompt;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources.ProductReviewSummary;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.examples.agents.CustomTypesAndResources.PRODUCT_SUGGESTION_PROMPT;

/**
 * An agent that uses a large language model (LLM) to generate actionable product improvement
 * suggestions from aggregated product review data.
 *
 * <p>This agent receives a summary of product reviews, including a rating distribution and a list
 * of user dissatisfaction reasons, and produces concrete suggestions for product enhancement. It
 * handles prompt construction, LLM interaction, and output parsing.
 */
public class ProductSuggestionAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "id";
    private static final String SCORE_HIST = "score_hist";

    @ChatModelSetup
    public static ResourceDescriptor generateSuggestionModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("extract_reasoning", "true")
                .addInitialArgument("prompt", "productSuggestionPrompt")
                .build();
    }

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt productSuggestionPrompt() {
        return PRODUCT_SUGGESTION_PROMPT;
    }

    /** Process input event. */
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        String input = (String) event.getInput();

        ProductReviewSummary summary = MAPPER.readValue(input, ProductReviewSummary.class);

        ctx.getShortTermMemory().set(ID, summary.getId());
        ctx.getShortTermMemory().set(SCORE_HIST, summary.getScoreHist());

        String content =
                String.format(
                        "{\n\"id\": %s,\n\"score_histogram\": %s,\n\"unsatisfied_reasons\": %s\n}",
                        summary.getId(), summary.getScoreHist(), summary.getUnsatisfiedReasons());

        ChatMessage msg = new ChatMessage(MessageRole.USER, "", Map.of("input", content));

        ctx.sendEvent(new ChatRequestEvent("generateSuggestionModel", List.of(msg)));
    }

    /** Process chat response event. */
    @Action(listenEvents = {ChatResponseEvent.class})
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx)
            throws Exception {
        JsonNode jsonNode = MAPPER.readTree(event.getResponse().getContent());
        JsonNode suggestionsNode = jsonNode.findValue("suggestion_list");
        List<String> suggestions = new ArrayList<>();
        if (suggestionsNode.isArray()) {
            for (JsonNode node : suggestionsNode) {
                suggestions.add(node.asText());
            }
        }

        ctx.sendEvent(
                new OutputEvent(
                        new CustomTypesAndResources.ProductSuggestion(
                                ctx.getShortTermMemory().get(ID).getValue().toString(),
                                (List<String>) ctx.getShortTermMemory().get(SCORE_HIST).getValue(),
                                suggestions)));
    }
}
