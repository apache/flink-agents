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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources.SentimentKeySelector;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources.SentimentRequest;
import org.apache.flink.agents.examples.agents.ParallelChatAgent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Java example demonstrating parallel LLM invocations via multi-action fan-out.
 *
 * <p>This example demonstrates how to use the Flink Agents to analyze a restaurant review by
 * fanning out multiple parallel LLM calls — one per sentiment dimension — and aggregating the
 * results with a final LLM call. This serves as a minimal, end-to-end example of integrating
 * parallel LLM-powered agents with Flink streaming jobs.
 */
public class ParallelChatRequestExample {

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Set up the Flink streaming environment and the Agents execution environment.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Add Ollama chat model connection to be used by the ParallelChatAgent.
        agentsEnv.addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .addInitialArgument("requestTimeout", 240)
                        .build());

        // Create input stream with a single restaurant review.
        DataStream<SentimentRequest> inputStream =
                env.fromElements(new SentimentRequest(1, ParallelChatAgent.INPUT_TEXT));

        // Use the ParallelChatAgent to analyze the review with parallel LLM calls.
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new SentimentKeySelector())
                        .apply(new ParallelChatAgent())
                        .toDataStream();

        // Print the analysis results to stdout.
        outputStream.print();

        // Execute the Flink pipeline.
        agentsEnv.execute();
    }
}
