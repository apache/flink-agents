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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.agents.examples.agents.ModelRoutingAgent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Java example demonstrating framework-managed LLM-as-judge model routing.
 *
 * <p>Like {@link ModelRoutingExample}, but the routing decision is made by a judge chat model
 * instead of regex rules: {@code Strategies.llm("judge")} points at a registered chat model, and
 * the engine runs the judge call on its own durable, metered, observable chat path (durable id
 * {@code "judge:<router>"}; judge tokens attributed to the judge model). The candidate {@code
 * describe(...)} lines are the judge's decision criteria. An unparseable verdict or a verdict
 * naming a non-candidate abstains to the default model; a failed judge call honors the
 * error-handling strategy (FAIL surfaces it, IGNORE abstains). The judge must be a plain chat model
 * — no bound prompt or tools.
 *
 * <p>Model names are illustrative — adjust them to models available on your Ollama server.
 */
public class ModelRoutingJudgeExample {

    private static ResourceDescriptor ollamaModel(String model) {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", model)
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

        // Ollama connection shared by both candidate models.
        agentsEnv.addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);

        // Two candidate chat models: a small (cheap) and a big (strong) one.
        agentsEnv
                .addResource("small", ResourceType.CHAT_MODEL, ollamaModel("qwen3:1.7b"))
                .addResource("big", ResourceType.CHAT_MODEL, ollamaModel("qwen3:8b"));

        // The judge is just another registered chat model.
        agentsEnv.addResource("judge", ResourceType.CHAT_MODEL, ollamaModel("qwen3:1.7b"));

        // A router whose decision comes from the judge; the descriptions are its criteria.
        agentsEnv.addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .describe("small", "fast and cheap: chit-chat, lookups, short factual asks")
                        .describe("big", "expensive: code, SQL, math, multi-step reasoning")
                        .strategy(Strategies.llm("judge"))
                        .defaultModel("small")
                        .fallback(true)
                        .build());

        // A small stream of requests: an easy one (-> small) and a coding one (-> big).
        DataStream<String> requestStream =
                env.fromData(
                        "Hi, how are you today?",
                        "Write SQL to select all active users from the users table.");

        DataStream<Object> resultStream =
                agentsEnv
                        .fromDataStream(requestStream)
                        .apply(new ModelRoutingAgent())
                        .toDataStream();

        resultStream.print();

        agentsEnv.execute("Model Routing Judge Example Job");
    }
}
