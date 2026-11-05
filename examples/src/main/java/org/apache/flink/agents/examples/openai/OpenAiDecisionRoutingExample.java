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
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.List;
import java.util.Map;

/**
 * Routes each request with a decision model instead of an LLM judge: the OpenAI Decisions API
 * answers one choice question over the candidates and reports a probability per candidate. The
 * chosen candidate answers unless its probability is below the configured minimum, in which case
 * the default model does. Requires {@code OPENAI_API_KEY} in the environment.
 *
 * <p>The decision model is the built-in Python OpenAI decision model provider, used from this Java
 * job through the cross-language resource API, so the TaskManagers need a Python environment with
 * {@code flink-agents} installed (see the cross-language resources documentation). The output lines
 * carry the model that answered, the decision source and the routing score, read from the {@code
 * model_routing} attribute of the response.
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

        // The decision model: the Python OpenAI decision model provider (connection and setup),
        // declared with Python resource descriptors and otherwise registered like any other
        // decision model resource.
        agentsEnv.addResource(
                "openaiDecisions",
                ResourceType.DECISION_MODEL_CONNECTION,
                PythonResourceDescriptor.Builder.newBuilder(
                                ResourceName.DecisionModel.Python.OPENAI_CONNECTION)
                        .addInitialArgument("api_key", apiKey)
                        .build());
        agentsEnv.addResource(
                "dm",
                ResourceType.DECISION_MODEL,
                PythonResourceDescriptor.Builder.newBuilder(
                                ResourceName.DecisionModel.Python.OPENAI_SETUP)
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
}
