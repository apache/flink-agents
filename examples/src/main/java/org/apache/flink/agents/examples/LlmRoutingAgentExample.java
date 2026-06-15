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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.model.routing.ChatModelRouter;
import org.apache.flink.agents.api.chat.model.routing.LlmRoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.RuleBasedRoutingStrategy;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.Row;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.examples.WorkflowSingleAgentExample.copyResource;

/**
 * Java example demonstrating pluggable LLM routing.
 *
 * <p>Two Ollama models are registered as routable candidates — a small/cheap model and a larger
 * model — together with a {@link ChatModelRouter} that is itself registered as a {@code
 * CHAT_MODEL}. The router picks, per request, which candidate should serve it. Because the router
 * is a drop-in chat model, an ordinary {@link ReActAgent} simply points at it; nothing else in the
 * pipeline changes.
 *
 * <p>This example uses the built-in {@link RuleBasedRoutingStrategy} (deterministic, no extra model
 * call): requests mentioning code/SQL/errors go to the larger model, everything else to the small
 * one. {@link #llmRoutingStrategy()} shows how to swap in the {@link LlmRoutingStrategy}
 * (LLM-as-router) instead, and any user-supplied {@code RoutingStrategy} class can be plugged in
 * the same way.
 *
 * <p>Prerequisite: a local Ollama server with the two models pulled (see {@code model} values
 * below).
 */
public class LlmRoutingAgentExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SMALL_MODEL = "smallModel";
    private static final String BIG_MODEL = "bigModel";

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        // 1) One shared connection, and two candidate chat-model setups registered by name.
        // 2) The router, registered as a CHAT_MODEL, that routes between the two candidates.
        agentsEnv
                .addResource(
                        "ollamaChatModelConnection",
                        ResourceType.CHAT_MODEL_CONNECTION,
                        CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR)
                .addResource(SMALL_MODEL, ResourceType.CHAT_MODEL, candidate("qwen3:1.7b"))
                .addResource(BIG_MODEL, ResourceType.CHAT_MODEL, candidate("qwen3:8b"));

        File inputDataFile = copyResource("input_data.txt");

        DataStream<Row> productReviewStream =
                env.fromSource(
                                FileSource.forRecordStreamFormat(
                                                new TextLineInputFormat(),
                                                new Path(inputDataFile.getAbsolutePath()))
                                        .monitorContinuously(Duration.ofMinutes(1))
                                        .build(),
                                WatermarkStrategy.noWatermarks(),
                                "llm-routing-example")
                        .map(
                                inputStr -> {
                                    Row row = Row.withNames();
                                    CustomTypesAndResources.ProductReview productReview =
                                            MAPPER.readValue(
                                                    inputStr,
                                                    CustomTypesAndResources.ProductReview.class);
                                    row.setField("id", productReview.getId());
                                    row.setField("review", productReview.getReview());
                                    return row;
                                });

        // The agent uses the router as its chat model — routing is fully transparent to the agent.
        // Swap ruleBasedStrategy() for llmRoutingStrategy() to route with an LLM-as-router instead.
        ReActAgent routedAgent =
                new ReActAgent(
                        routerDescriptor(ruleBasedStrategy()),
                        CustomTypesAndResources.REVIEW_ANALYSIS_REACT_PROMPT,
                        CustomTypesAndResources.ProductReviewAnalysisRes.class);

        DataStream<Object> resultStream =
                agentsEnv.fromDataStream(productReviewStream).apply(routedAgent).toDataStream();

        resultStream.print();

        agentsEnv.execute();
    }

    /** A candidate chat-model setup sharing the registered Ollama connection. */
    static ResourceDescriptor candidate(String model) {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", model)
                .build();
    }

    /** Candidate specs the router and strategies reason about (name + description). */
    static List<Object> candidateSpecs() {
        List<Object> candidates = new ArrayList<>();
        Map<String, Object> small = new HashMap<>();
        small.put("name", SMALL_MODEL);
        small.put("description", "Fast, cheap model for simple chit-chat and short answers.");
        Map<String, Object> big = new HashMap<>();
        big.put("name", BIG_MODEL);
        big.put("description", "Stronger model for code, SQL, and complex reasoning.");
        candidates.add(small);
        candidates.add(big);
        return candidates;
    }

    /** A router resource descriptor wrapping the given strategy. */
    static ResourceDescriptor routerDescriptor(ResourceDescriptor strategy) {
        return ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                .addInitialArgument(ChatModelRouter.ARG_CANDIDATES, candidateSpecs())
                .addInitialArgument(ChatModelRouter.ARG_STRATEGY, strategy)
                .addInitialArgument(ChatModelRouter.ARG_FALLBACK, true)
                .build();
    }

    /**
     * Built-in rule-based strategy: keywords route to the larger model; otherwise the small one.
     */
    static ResourceDescriptor ruleBasedStrategy() {
        Map<String, Object> codeRule = new HashMap<>();
        codeRule.put("model", BIG_MODEL);
        codeRule.put("keywords", Arrays.asList("code", "sql", "error", "exception", "stacktrace"));

        return ResourceDescriptor.Builder.newBuilder(RuleBasedRoutingStrategy.class.getName())
                .addInitialArgument("default", SMALL_MODEL)
                .addInitialArgument("rules", Collections.singletonList(codeRule))
                .build();
    }

    /**
     * Built-in LLM-as-router strategy: a small judge model chooses the candidate. Swap this into
     * {@link #routerDescriptor(ResourceDescriptor)} to use it instead of the rule-based strategy.
     */
    static ResourceDescriptor llmRoutingStrategy() {
        return ResourceDescriptor.Builder.newBuilder(LlmRoutingStrategy.class.getName())
                .addInitialArgument("judge_model", SMALL_MODEL)
                .addInitialArgument("default", SMALL_MODEL)
                .build();
    }
}
