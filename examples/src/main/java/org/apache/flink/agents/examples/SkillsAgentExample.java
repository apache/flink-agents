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
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.agents.examples.agents.MathAgent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Java example demonstrating Agent Skills with Flink Agents.
 *
 * <p>A stream of arithmetic questions is processed by {@link MathAgent}, whose chat model has a
 * {@code math-calculator} skill enabled. For each question the model loads the skill and follows
 * its instructions to compute the answer with the {@code bc} calculator, then the result is printed
 * to stdout. This serves as a minimal, end-to-end example of integrating skill-powered agents with
 * Flink streaming jobs.
 */
public class SkillsAgentExample {

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Set up the Flink streaming environment and the Agents execution environment.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // limit async request to avoid overwhelming ollama server
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        // Add Ollama chat model connection to be used by the MathAgent.
        agentsEnv.addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);

        // A small stream of arithmetic questions to answer.
        DataStream<String> questionStream =
                env.fromData(
                        "What is (2 + 3) * 4?", "Compute 2 ^ 10.", "What is 144 divided by 12?");

        // Use the MathAgent to answer each question with the math-calculator skill.
        DataStream<Object> answerStream =
                agentsEnv.fromDataStream(questionStream).apply(new MathAgent()).toDataStream();

        // Print the answers to stdout.
        answerStream.print();

        // Execute the Flink pipeline with the Flink job name.
        agentsEnv.execute("Skills Agent Example Job");
    }
}
