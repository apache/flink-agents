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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import static org.apache.flink.agents.api.agents.Agent.STRUCTURED_OUTPUT;

/**
 * An agent that demonstrates parallel LLM invocations via fan-out of multiple {@link
 * ChatRequestEvent} events.
 *
 * <p>This agent receives a restaurant review and uses an LLM to judge sentiment along multiple
 * dimensions (taste / service / price) in parallel, then aggregates the results into a one-line
 * summary with a final LLM call. It handles prompt construction, parallel chat dispatch, response
 * accumulation, and output assembly.
 *
 * <p><b>JDK version note:</b> On JDK 21+, the framework uses the Continuation API to execute
 * concurrent chat actions in parallel, so the wall clock time is roughly "slowest single branch +
 * aggregation call". On JDK &lt; 21, the framework silently falls back to sequential execution —
 * the result is identical, but the LLM calls run one after another.
 */
public class ParallelChatAgent extends Agent {

    /** Ollama model name, configurable via environment variable. */
    public static final String OLLAMA_MODEL =
            System.getenv().getOrDefault("PARALLEL_CHAT_OLLAMA_MODEL", "qwen3:1.7b");

    /** Input text for the demo. */
    public static final String INPUT_TEXT = "The food here is great, but the service is too slow";

    private static final String[] ASPECTS = {"taste", "service", "price"};
    private static final int N_ASPECTS = ASPECTS.length;

    private static final String PARALLEL_SYSTEM_PROMPT =
            "You are a sentiment analysis assistant. Return JSON: "
                    + "{\"aspect\":\"<dimension>\", \"result\":\"<positive|negative|not_mentioned>\"}"
                    + " — no explanation, no extra fields.";
    private static final String AGGREGATE_SYSTEM_PROMPT =
            "You are a summary assistant. Based on the sentiment judgments for three "
                    + "dimensions, compose a brief one-line evaluation. Return JSON: "
                    + "{\"summary\":\"taste:<positive/negative/not_mentioned>, "
                    + "service:<positive/negative/not_mentioned>, "
                    + "price:<positive/negative/not_mentioned>\"} — return only this JSON.";

    @ChatModelSetup
    public static ResourceDescriptor sentimentModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", OLLAMA_MODEL)
                .addInitialArgument("extract_reasoning", true)
                .build();
    }

    private static Map<String, Object> initRow(Event event) {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        CustomTypesAndResources.SentimentRequest request =
                (CustomTypesAndResources.SentimentRequest) inputEvent.getInput();
        Map<String, Object> row = new HashMap<>();
        row.put("id", request.getId());
        row.put("text", request.getText());
        row.put("sentiments", new HashMap<String, String>());
        row.put("aspect_map", new HashMap<String, String>());
        return row;
    }

    private static ChatRequestEvent buildAspectRequest(String text, String aspect) {
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, PARALLEL_SYSTEM_PROMPT),
                        new ChatMessage(
                                MessageRole.USER,
                                "Judge the \"" + aspect + "\" dimension: " + text));
        return new ChatRequestEvent(
                "sentimentModel", messages, CustomTypesAndResources.AspectResponse.class);
    }

    @SuppressWarnings("unchecked")
    private static ChatRequestEvent buildSummarizeRequest(Map<String, Object> row) {
        Map<String, String> sentiments = (Map<String, String>) row.get("sentiments");
        StringJoiner sj = new StringJoiner(" ");
        for (String aspect : ASPECTS) {
            sj.add(aspect + ":" + sentiments.get(aspect));
        }
        String body = "Original: " + row.get("text") + "\nJudgments: " + sj;
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, AGGREGATE_SYSTEM_PROMPT),
                        new ChatMessage(MessageRole.USER, body));
        return new ChatRequestEvent(
                "sentimentModel", messages, CustomTypesAndResources.SummaryResponse.class);
    }

    private static OutputEvent buildOutputEvent(
            Map<String, Object> row, CustomTypesAndResources.SummaryResponse parsed) {
        Map<String, Object> output = new HashMap<>();
        output.put("id", row.get("id"));
        output.put("text", row.get("text"));
        output.put("summary", parsed.summary);
        return new OutputEvent(output);
    }

    @SuppressWarnings("unchecked")
    private static boolean allAspectsReceived(Map<String, Object> row) {
        Map<String, String> sentiments = (Map<String, String>) row.get("sentiments");
        return sentiments.size() == N_ASPECTS;
    }

    /** Process input event and fan-out chat requests for each aspect. */
    @SuppressWarnings("unchecked")
    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void requestAspectJudgments(Event event, RunnerContext ctx) throws Exception {
        Map<String, Object> row = initRow(event);
        List<ChatRequestEvent> requests = new ArrayList<>();
        Map<String, String> aspectMap = (Map<String, String>) row.get("aspect_map");
        for (String aspect : ASPECTS) {
            ChatRequestEvent req = buildAspectRequest((String) row.get("text"), aspect);
            aspectMap.put(req.getId().toString(), aspect);
            requests.add(req);
        }
        // Sensory memory stores the Map directly in Java; no JSON serialization required.
        ctx.getSensoryMemory().set("res", row);
        for (ChatRequestEvent req : requests) {
            ctx.sendEvent(req);
        }
    }

    /** Process chat response event. */
    @SuppressWarnings("unchecked")
    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void handleResponse(Event event, RunnerContext ctx) throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        Object parsed = chatResponse.getResponse().getExtraArgs().get(STRUCTURED_OUTPUT);
        Map<String, Object> row =
                (Map<String, Object>) ctx.getSensoryMemory().get("res").getValue();

        if (parsed instanceof CustomTypesAndResources.SummaryResponse) {
            CustomTypesAndResources.SummaryResponse summary =
                    (CustomTypesAndResources.SummaryResponse) parsed;
            ctx.sendEvent(buildOutputEvent(row, summary));
            return;
        }

        CustomTypesAndResources.AspectResponse aspectResponse =
                (CustomTypesAndResources.AspectResponse) parsed;
        Map<String, String> sentiments = (Map<String, String>) row.get("sentiments");
        Map<String, String> aspectMap = (Map<String, String>) row.get("aspect_map");
        String dispatchedAspect = aspectMap.get(chatResponse.getRequestId().toString());
        sentiments.put(dispatchedAspect, aspectResponse.result);
        // Sensory memory stores the Map directly in Java; no JSON serialization required.
        ctx.getSensoryMemory().set("res", row);
        if (allAspectsReceived(row)) {
            ctx.sendEvent(buildSummarizeRequest(row));
        }
    }
}
