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
import org.apache.flink.agents.examples.agents.ModelRoutingAgent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Map;

/**
 * Rule-based in-chat model routing against OpenAI, runnable as a local Flink job.
 *
 * <p>Routes coding/SQL/analysis requests to a strong model ({@code gpt-4o}) and everything else to
 * a small model ({@code gpt-4o-mini}); on no match it abstains and the router uses its default
 * ({@code small}). The selected model call is a first-class chat in the EventLog (tokens attributed
 * to that model) and the decision is recorded as a {@code ModelRoutingEvent}.
 *
 * <p>Run with {@code OPENAI_API_KEY} set in the environment.
 */
public class OpenAiModelRoutingExample {

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

        // OpenAI connection shared by both candidate models.
        agentsEnv.addResource(
                "openaiConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(
                                ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                        .addInitialArgument("api_key", apiKey)
                        .build());

        // Two candidate chat models: a small (cheap) and a big (strong) one.
        agentsEnv
                .addResource("small", ResourceType.CHAT_MODEL, openAiModel("gpt-4o-mini"))
                .addResource("big", ResourceType.CHAT_MODEL, openAiModel("gpt-4o"));

        // Router: code/SQL/analysis -> "big"; otherwise abstain -> default "small".
        agentsEnv.addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .strategy(
                                Strategies.rules(
                                        Map.of("big", "\\b(code|sql|program|analyze|prove)\\b")))
                        .defaultModel("small")
                        .build());

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

        agentsEnv.execute("OpenAI Model Routing Example");
    }
}
