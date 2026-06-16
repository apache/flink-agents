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
 * dimensions (taste / service) in parallel, then aggregates the results into a one-line summary
 * with a final LLM call. It handles prompt construction, parallel chat dispatch, response
 * accumulation, and output assembly.
 *
 * <p>Event flow:
 *
 * <ol>
 *   <li>InputEvent → requestAspectJudgments → emits SentimentInputEvent
 *   <li>SentimentInputEvent triggers handlers in parallel:
 *       <ul>
 *         <li>handleTasteInput → ChatRequestEvent (taste LLM call)
 *         <li>handleServiceInput → ChatRequestEvent (service LLM call)
 *       </ul>
 *   <li>Each ChatResponseEvent → handleResponse (accumulates aspect results)
 *   <li>Once all aspects received → aggregation LLM call → OutputEvent
 * </ol>
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

    private static final String[] ASPECTS = {"taste", "service"};

    private static final String SENTIMENT_INPUT_EVENT_TYPE = "SentimentInputEvent";

    private static final String PARALLEL_SYSTEM_PROMPT =
            "You are a sentiment analysis assistant. Return JSON: "
                    + "{\"aspect\":\"<dimension>\", \"result\":\"<positive|negative|not_mentioned>\"}"
                    + " — no explanation, no extra fields.";
    private static final String AGGREGATE_SYSTEM_PROMPT =
            "You are a summary assistant. Based on the sentiment judgments for two "
                    + "dimensions, compose a brief one-line evaluation. Return JSON: "
                    + "{\"summary\":\"taste:<positive/negative/not_mentioned>, "
                    + "service:<positive/negative/not_mentioned>\"} — return only this JSON.";

    @ChatModelSetup
    public static ResourceDescriptor sentimentModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", OLLAMA_MODEL)
                .addInitialArgument("extract_reasoning", true)
                .build();
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

    private static ChatRequestEvent buildSummarizeRequest(
            String text, Map<String, String> sentiments) {
        StringJoiner sj = new StringJoiner(" ");
        for (String aspect : ASPECTS) {
            sj.add(aspect + ":" + sentiments.get(aspect));
        }
        String body = "Original: " + text + "\nJudgments: " + sj;
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, AGGREGATE_SYSTEM_PROMPT),
                        new ChatMessage(MessageRole.USER, body));
        return new ChatRequestEvent(
                "sentimentModel", messages, CustomTypesAndResources.SummaryResponse.class);
    }

    private static OutputEvent buildOutputEvent(
            int id, String text, CustomTypesAndResources.SummaryResponse parsed) {
        Map<String, Object> output = new HashMap<>();
        output.put("id", id);
        output.put("text", text);
        output.put("summary", parsed.summary);
        return new OutputEvent(output);
    }

    /** Process input event and dispatch a SentimentInputEvent for each aspect handler. */
    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void requestAspectJudgments(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        CustomTypesAndResources.SentimentRequest request =
                (CustomTypesAndResources.SentimentRequest) inputEvent.getInput();
        ctx.getSensoryMemory().set("id", request.getId());
        ctx.getSensoryMemory().set("text", request.getText());
        ctx.sendEvent(
                new Event(
                        SENTIMENT_INPUT_EVENT_TYPE,
                        Map.of("input_id", request.getId(), "text", request.getText())));
    }

    /** Handle taste aspect: build and send ChatRequestEvent for taste judgment. */
    @Action(listenEventTypes = {SENTIMENT_INPUT_EVENT_TYPE})
    public static void handleTasteInput(Event event, RunnerContext ctx) throws Exception {
        ChatRequestEvent req = buildAspectRequest((String) event.getAttr("text"), "taste");
        ctx.getSensoryMemory().set("aspect_map." + req.getId(), "taste");
        ctx.sendEvent(req);
    }

    /** Handle service aspect: build and send ChatRequestEvent for service judgment. */
    @Action(listenEventTypes = {SENTIMENT_INPUT_EVENT_TYPE})
    public static void handleServiceInput(Event event, RunnerContext ctx) throws Exception {
        ChatRequestEvent req = buildAspectRequest((String) event.getAttr("text"), "service");
        ctx.getSensoryMemory().set("aspect_map." + req.getId(), "service");
        ctx.sendEvent(req);
    }

    /** Process chat response event. */
    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void handleResponse(Event event, RunnerContext ctx) throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        Object parsed = chatResponse.getResponse().getExtraArgs().get(STRUCTURED_OUTPUT);

        if (parsed instanceof CustomTypesAndResources.SummaryResponse) {
            CustomTypesAndResources.SummaryResponse summary =
                    (CustomTypesAndResources.SummaryResponse) parsed;
            int id = (int) ctx.getSensoryMemory().get("id").getValue();
            String text = (String) ctx.getSensoryMemory().get("text").getValue();
            ctx.sendEvent(buildOutputEvent(id, text, summary));
            return;
        }

        CustomTypesAndResources.AspectResponse aspectResponse =
                (CustomTypesAndResources.AspectResponse) parsed;
        String aspect =
                (String)
                        ctx.getSensoryMemory()
                                .get("aspect_map." + chatResponse.getRequestId())
                                .getValue();
        ctx.getSensoryMemory().set("sentiments." + aspect, aspectResponse.result);
        boolean allReceived = true;
        for (String a : ASPECTS) {
            if (!ctx.getSensoryMemory().isExist("sentiments." + a)) {
                allReceived = false;
                break;
            }
        }
        if (allReceived) {
            String text = (String) ctx.getSensoryMemory().get("text").getValue();
            Map<String, String> sentiments = new HashMap<>();
            for (String a : ASPECTS) {
                sentiments.put(
                        a, (String) ctx.getSensoryMemory().get("sentiments." + a).getValue());
            }
            ctx.sendEvent(buildSummarizeRequest(text, sentiments));
        }
    }
}
