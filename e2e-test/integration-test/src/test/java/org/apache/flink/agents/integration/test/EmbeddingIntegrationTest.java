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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.apache.flink.agents.integration.test.EmbeddingIntegrationAgent.OLLAMA_MODEL;
import static org.apache.flink.agents.integration.test.OllamaPreparationUtils.pullModel;

/**
 * Example application that applies {@link EmbeddingIntegrationAgent} to a DataStream of prompts.
 */
public class EmbeddingIntegrationTest {
    private static final String API_KEY = "_API_KEY";
    private static final String OLLAMA = "OLLAMA";

    private final boolean ollamaReady;

    public EmbeddingIntegrationTest() throws IOException {
        ollamaReady = pullModel(OLLAMA_MODEL);
    }

    @ParameterizedTest()
    @ValueSource(strings = {"OLLAMA"})
    public void testEmbeddingIntegration(String provider) throws Exception {
        Assumptions.assumeTrue(
                (OLLAMA.equals(provider) && ollamaReady)
                        || System.getenv().get(provider + API_KEY) != null,
                String.format(
                        "Server or authentication information is not provided for %s", provider));

        System.setProperty("MODEL_PROVIDER", provider);

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
                        .apply(new EmbeddingIntegrationAgent())
                        .toDataStream();

        // Print the results
        outputStream.print();

        // Execute the pipeline
        agentsEnv.execute();
    }
}
