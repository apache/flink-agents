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
package org.apache.flink.agents.examples.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelConnection;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes each request with a decision model instead of an LLM judge: the OpenAI Decisions API
 * answers one choice question over the candidates and reports a probability per candidate. The
 * chosen candidate answers unless its probability is below the configured minimum, in which case
 * the default model does. Requires {@code OPENAI_API_KEY} in the environment.
 *
 * <p>The decision model is a native Java provider: {@link OpenAiDecisionsConnection} implements
 * {@link BaseDecisionModelConnection} over the Decisions endpoint, so the job needs no Python
 * environment on the TaskManagers. The output lines carry the model that answered, the decision
 * source and the routing score, read from the {@code model_routing} attribute of the response.
 */
public class OpenAiDecisionRoutingExample {

    private static ResourceDescriptor openAiModel(String model) {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                .addInitialArgument("connection", "openaiConnection")
                .addInitialArgument("model", model)
                .build();
    }

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set OPENAI_API_KEY in the environment to run.");
        }
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        // OpenAI connection shared by both candidate chat models.
        agentsEnv.addResource(
                "openaiConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(
                                ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                        .addInitialArgument("api_key", apiKey)
                        .build());
        agentsEnv
                .addResource("small", ResourceType.CHAT_MODEL, openAiModel("gpt-4o-mini"))
                .addResource("big", ResourceType.CHAT_MODEL, openAiModel("gpt-4o"));

        // The decision model: a connection to the OpenAI Decisions API and a setup naming the
        // decision model. Registered like any other decision model resource.
        agentsEnv.addResource(
                "openaiDecisions",
                ResourceType.DECISION_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(OpenAiDecisionsConnection.class.getName())
                        .addInitialArgument("api_key", apiKey)
                        .build());
        agentsEnv.addResource(
                "dm",
                ResourceType.DECISION_MODEL,
                ResourceDescriptor.Builder.newBuilder(BaseDecisionModelSetup.class.getName())
                        .addInitialArgument("connection", "openaiDecisions")
                        .addInitialArgument("model", "gpt-6-luna")
                        .build());

        // A router whose decision comes from the decision model; the descriptions are the choice
        // criteria. A chosen candidate below 0.6 probability abstains to the default model.
        agentsEnv.addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .describe("small", "fast and cheap: chit-chat, lookups, short factual asks")
                        .describe("big", "expensive: code, SQL, math, multi-step reasoning")
                        .strategy(Strategies.decision("dm").withMinProbability(0.6))
                        .defaultModel("small")
                        .fallback(true)
                        .build());

        // An easy request (-> small) and a coding request (-> big).
        DataStream<String> requestStream =
                env.fromData(
                        "Hi, how are you today?",
                        "Write SQL to select all active users from the users table.");
        DataStream<Object> resultStream =
                agentsEnv
                        .fromDataStream(requestStream, value -> value)
                        .apply(new DecisionRoutingAgent())
                        .toDataStream();
        resultStream.print();
        agentsEnv.execute("OpenAI Decision Routing Example");
    }

    /** Sends each input to the router and reports which model answered and why. */
    public static class DecisionRoutingAgent extends Agent {

        @Action(EventType.InputEvent)
        public static void processInput(InputEvent event, RunnerContext ctx) {
            ctx.sendEvent(
                    new ChatRequestEvent(
                            "router", List.of(ChatMessage.user((String) event.getInput()))));
        }

        @Action(EventType.ChatResponseEvent)
        public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
            if (event.isFailed()) {
                ctx.sendEvent(new OutputEvent("FAILED: " + event.getError()));
                return;
            }
            Map<?, ?> routing = (Map<?, ?>) event.getAttr("model_routing");
            String answer = event.getResponse().getText().replace('\n', ' ');
            ctx.sendEvent(
                    new OutputEvent(
                            String.format(
                                    "final_model=%s decision_source=%s score=%s :: %s",
                                    routing.get("final_model"),
                                    routing.get("decision_source"),
                                    routing.get("score"),
                                    answer.length() > 80
                                            ? answer.substring(0, 80) + "..."
                                            : answer)));
        }
    }

    /**
     * A native Java decision model connection over the OpenAI Decisions API ({@code POST
     * /v1/decisions}). Supports choice and predicate questions, which is what routing and most
     * gating use; score questions are rejected. Descriptor arguments: {@code api_key} (required)
     * and {@code base_url} (default {@code https://api.openai.com/v1}).
     */
    public static class OpenAiDecisionsConnection extends BaseDecisionModelConnection {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final java.util.Set<String> RESERVED_FIELDS =
                java.util.Set.of("model", "input", "questions");

        private final String apiKey;
        private final String baseUrl;
        private transient volatile HttpClient client;

        public OpenAiDecisionsConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
            Object key = descriptor.getArgument("api_key");
            if (!(key instanceof String) || ((String) key).isBlank()) {
                throw new IllegalArgumentException(
                        "OpenAiDecisionsConnection requires a non-blank 'api_key'.");
            }
            this.apiKey = (String) key;
            this.baseUrl = descriptor.getArgument("base_url", "https://api.openai.com/v1");
        }

        @Override
        public DecisionResult decide(
                DecisionRequest request, String model, Map<String, Object> requestParams) {
            ObjectNode body = MAPPER.createObjectNode();
            for (Map.Entry<String, Object> param : requestParams.entrySet()) {
                if (RESERVED_FIELDS.contains(param.getKey())) {
                    throw new IllegalArgumentException(
                            "Request parameter '"
                                    + param.getKey()
                                    + "' cannot override the model, evidence or questions.");
                }
                body.set(param.getKey(), MAPPER.valueToTree(param.getValue()));
            }
            body.put("model", model);
            body.set("input", MAPPER.valueToTree(request.getInput()));
            ArrayNode questions = body.putArray("questions");
            for (Map.Entry<String, DecisionRequest.Question> entry :
                    request.getQuestions().entrySet()) {
                questions.add(toQuestion(entry.getKey(), entry.getValue()));
            }
            JsonNode response = post(body);
            Map<String, DecisionResult.Answer> answers = new LinkedHashMap<>();
            for (JsonNode answer : response.path("answers")) {
                String name = answer.path("name").asText();
                DecisionRequest.Question question = request.getQuestions().get(name);
                if (question == null) {
                    throw new IllegalStateException(
                            "OpenAI Decisions API answered an unknown question '" + name + "'.");
                }
                answers.put(name, toAnswer(answer, question));
            }
            return new DecisionResult(
                    answers,
                    response.path("model").isTextual() ? response.get("model").asText() : null,
                    toUsage(response.path("usage")),
                    Map.of());
        }

        private static ObjectNode toQuestion(String name, DecisionRequest.Question question) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", name);
            if (question instanceof DecisionRequest.ChoiceQuestion) {
                node.put("type", "choice");
                node.set("instructions", MAPPER.valueToTree(question.getInstructions()));
                ArrayNode choices = node.putArray("choices");
                for (Map.Entry<String, Object> option :
                        ((DecisionRequest.ChoiceQuestion) question).getCriteria().entrySet()) {
                    ObjectNode choice = choices.addObject();
                    choice.put("value", option.getKey());
                    if (option.getValue() != null) {
                        choice.set("description", MAPPER.valueToTree(option.getValue()));
                    }
                }
                return node;
            }
            if (question instanceof DecisionRequest.PredicateQuestion) {
                node.put("type", "predicate");
                node.set("instructions", MAPPER.valueToTree(question.getInstructions()));
                return node;
            }
            throw new IllegalArgumentException(
                    "OpenAiDecisionsConnection supports choice and predicate questions only.");
        }

        private static DecisionResult.Answer toAnswer(
                JsonNode answer, DecisionRequest.Question question) {
            String type = answer.path("type").asText();
            if ("refusal".equals(type)) {
                return new DecisionResult.RefusalAnswer(
                        answer.path("reason").isTextual() ? answer.get("reason").asText() : null);
            }
            if (question instanceof DecisionRequest.ChoiceQuestion) {
                if (!answer.path("choice").isTextual()) {
                    throw new IllegalStateException(
                            "OpenAI Decisions API returned a choice answer without a 'choice'.");
                }
                Map<String, Double> probabilities = new LinkedHashMap<>();
                for (JsonNode entry : answer.path("probabilities")) {
                    if (!entry.path("value").isTextual() || !entry.path("probability").isNumber()) {
                        throw new IllegalStateException(
                                "OpenAI Decisions API returned a malformed probability entry: "
                                        + entry);
                    }
                    if (probabilities.put(
                                    entry.path("value").asText(),
                                    entry.path("probability").asDouble())
                            != null) {
                        throw new IllegalStateException(
                                "OpenAI Decisions API returned duplicate choice '"
                                        + entry.path("value").asText()
                                        + "'.");
                    }
                }
                if (probabilities.isEmpty()) {
                    throw new IllegalStateException(
                            "OpenAI Decisions API returned a choice answer without"
                                    + " probabilities.");
                }
                return new DecisionResult.ChoiceAnswer(
                        answer.path("choice").asText(),
                        probabilities,
                        answer.path("confidence").isNumber()
                                ? answer.get("confidence").asDouble()
                                : null);
            }
            if (!"predicate".equals(type) || !answer.path("probability").isNumber()) {
                throw new IllegalStateException(
                        "OpenAI Decisions API returned an unexpected answer of type '"
                                + type
                                + "' for a predicate question.");
            }
            return new DecisionResult.PredicateAnswer(answer.path("probability").asDouble());
        }

        private static DecisionResult.TokenUsage toUsage(JsonNode usage) {
            if (usage.isMissingNode() || usage.isNull()) {
                return null;
            }
            return new DecisionResult.TokenUsage(
                    longOrNull(usage, "input_tokens", "prompt_tokens"),
                    longOrNull(usage, "output_tokens", "completion_tokens"),
                    longOrNull(usage, "total_tokens"));
        }

        private static Long longOrNull(JsonNode node, String... names) {
            for (String name : names) {
                if (node.path(name).isNumber()) {
                    return node.get(name).asLong();
                }
            }
            return null;
        }

        private JsonNode post(ObjectNode body) {
            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
            }
            try {
                HttpRequest request =
                        HttpRequest.newBuilder(URI.create(baseUrl + "/decisions"))
                                .timeout(Duration.ofSeconds(60))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                MAPPER.writeValueAsString(body)))
                                .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "OpenAI Decisions API returned HTTP "
                                    + response.statusCode()
                                    + ": "
                                    + errorMessage(response.body()));
                }
                return MAPPER.readTree(response.body());
            } catch (IOException e) {
                throw new IllegalStateException("OpenAI Decisions API call failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenAI Decisions API call interrupted", e);
            }
        }

        /** The provider's error message without echoing the whole body into logs. */
        private static String errorMessage(String body) {
            try {
                JsonNode error = MAPPER.readTree(body).path("error");
                Iterator<String> fields = error.fieldNames();
                return fields.hasNext() ? error.path("message").asText(body) : body;
            } catch (IOException e) {
                return body;
            }
        }
    }
}
