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

package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/** Example application that applies {@link AgentWithOllamaEmbedding} to a DataStream of prompts. */
public class AgentWithOllamaEmbeddingExample {
    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Use prompts that exercise embedding generation and similarity checks
        DataStream<String> inputStream =
                env.fromData(
                        "Generate embedding for: 'Machine learning'",
                        "Generate embedding for: 'Deep learning techniques'",
                        "Find texts similar to: 'neural networks'",
                        "Produce embedding and return top-3 similar items for: 'natural language processing'",
                        "Generate embedding for: 'hello world'",
                        "Compare similarity between 'cat' and 'dog'",
                        "Create embedding for: 'space exploration'",
                        "Find nearest neighbors for: 'artificial intelligence'",
                        "Generate embedding for: 'data science'",
                        "Random embedding test");

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream and use the prompt itself as the key
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<String, String>) value -> value)
                        .apply(new AgentWithOllamaEmbedding())
                        .toDataStream();

        // Print the results
        outputStream.print();

        // Execute the pipeline
        agentsEnv.execute();
    }
}
