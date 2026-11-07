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
package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java example demonstrating quality-triggered escalation between two Ollama models.
 *
 * <p>Every request starts on the cheapest tier ({@code small}). After each model round the router
 * runs its built-in structural checks and the declared quality signals on the answer; when one of
 * them rejects it, the same request is answered again by the next tier ({@code big}) and the hop is
 * recorded in the response's {@code model_routing} attribute and as a {@code ModelRoutingEvent}
 * with {@code decision_source: escalation}. The agent prints the model that answered and the hops.
 *
 * <p>The custom signal below is a cheap domain check: a request that asks for SQL must be answered
 * with a SQL statement, and an answer that hedges is sent up as well. A decision model can be used
 * as a signal instead ({@code QualitySignals.decision("verifier", 0.7)}); see the Model Routing
 * documentation.
 *
 * <p>Model names are illustrative — adjust them to models available on your Ollama server.
 */
public class ModelRoutingEscalationExample {

    /**
     * Rejects an answer to a SQL request that contains no SQL statement, and any answer that hedges
     * instead of answering. Signals are instantiated by class name on the task managers, so they
     * need a public no-argument constructor (or one taking a {@code Map} of arguments).
     */
    public static class SqlAnswerSignal implements QualitySignal {
        private static final Pattern SQL_REQUEST =
                Pattern.compile("\\bsql\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern SQL_STATEMENT =
                Pattern.compile(
                        "\\b(select|insert|update|delete|with)\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern HEDGE =
                Pattern.compile(
                        "\\b(i don't know|i do not know|i'm not sure|i am not sure|cannot help)\\b",
                        Pattern.CASE_INSENSITIVE);

        @Override
        public QualityVerdict evaluate(QualityContext context) {
            String answer = context.getResponse().getText();
            if (HEDGE.matcher(answer).find()) {
                return QualityVerdict.escalate("the answer hedges instead of answering");
            }
            String request = context.lastUserMessage();
            if (SQL_REQUEST.matcher(request).find() && !SQL_STATEMENT.matcher(answer).find()) {
                return QualityVerdict.escalate(
                        "a SQL request was answered without a SQL statement");
            }
            return QualityVerdict.accept();
        }
    }

    private static ResourceDescriptor ollamaModel(String model) {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", model)
                .addInitialArgument("think", false)
                .build();
    }

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // limit async request to avoid overwhelming ollama server
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        agentsEnv.addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);

        // Two tiers, cheapest first.
        agentsEnv
                .addResource("small", ResourceType.CHAT_MODEL, ollamaModel("qwen3:0.6b"))
                .addResource("big", ResourceType.CHAT_MODEL, ollamaModel("qwen3:8b"));

        // No routing strategy: every request starts on "small" and is escalated to "big" when the
        // structural checks or the SQL signal reject the answer. Fallback still covers failures.
        agentsEnv.addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .escalation(
                                Escalation.tiers("small", "big")
                                        .when(QualitySignals.custom(SqlAnswerSignal.class)))
                        .fallback(true)
                        .build());

        // Four requests: a greeting and a plain SQL request stay on "small"; a request that asks
        // for SQL "in prose only" usually makes the small model skip the statement, so the SQL
        // signal escalates it; an unanswerable fact makes both tiers hedge, so the request is
        // escalated once and the last tier's answer is kept.
        DataStream<String> requestStream =
                env.fromData(
                        "Hi, how are you today?",
                        "Write SQL to select all active users from the users table.",
                        "Write SQL that lists, per customer, the last order date; answer in prose"
                                + " only, without any SQL code.",
                        "If you are not sure, answer exactly: I'm not sure. What was the closing"
                                + " price of Acme Corp stock on 4 November 2026?");

        DataStream<Object> resultStream =
                agentsEnv
                        .fromDataStream(requestStream, value -> value)
                        .apply(new EscalationReportingAgent())
                        .toDataStream();

        resultStream.print();

        agentsEnv.execute("Model Routing Escalation Example Job");
    }

    /** Sends each input to the router and reports which tier answered and why. */
    public static class EscalationReportingAgent extends Agent {

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
            StringBuilder hops = new StringBuilder();
            for (Object hop : (List<?>) routing.get("escalations")) {
                Map<?, ?> h = (Map<?, ?>) hop;
                hops.append(
                        String.format(
                                " [%s -> %s: %s]", h.get("from"), h.get("to"), h.get("reason")));
            }
            String answer = event.getResponse().getText().replace('\n', ' ');
            ctx.sendEvent(
                    new OutputEvent(
                            String.format(
                                    Locale.ROOT,
                                    "final_model=%s decision_source=%s escalated_tokens=%s%s :: %s",
                                    routing.get("final_model"),
                                    routing.get("decision_source"),
                                    routing.get("escalated_tokens"),
                                    hops,
                                    answer.length() > 80
                                            ? answer.substring(0, 80) + "..."
                                            : answer)));
        }
    }
}
