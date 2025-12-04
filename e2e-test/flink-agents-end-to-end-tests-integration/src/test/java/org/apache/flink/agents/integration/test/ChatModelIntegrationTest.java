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
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.apache.flink.agents.integration.test.ChatModelIntegrationAgent.OLLAMA_MODEL;

/**
 * Example application that applies {@link ChatModelIntegrationAgent} to a DataStream of user
 * prompts.
 */
public class ChatModelIntegrationTest extends OllamaPreparationUtils {
    private static final Logger LOG = LoggerFactory.getLogger(ChatModelIntegrationTest.class);

    private static final String API_KEY = "_API_KEY";
    private static final String OLLAMA = "OLLAMA";
    private static final String PYTHON = "PYTHON";

    private final boolean ollamaReady;

    public ChatModelIntegrationTest() throws IOException {
        ollamaReady = pullModel(OLLAMA_MODEL);
    }

    @ParameterizedTest()
    @ValueSource(strings = {"OLLAMA", "AZURE", "OPENAI", "PYTHON"})
    public void testChatModeIntegration(String provider) throws Exception {
        Assumptions.assumeTrue(
                ((OLLAMA.equals(provider) || PYTHON.equals(provider)) && ollamaReady)
                        || System.getenv().get(provider + API_KEY) != null,
                String.format(
                        "Server or authentication information is not provided for %s", provider));

        System.setProperty("MODEL_PROVIDER", provider);

        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Use prompts that trigger different tool calls in the agent
        DataStream<String> inputStream =
                env.fromData(
                        "Convert 25 degrees Celsius to Fahrenheit",
                        "What is 98.6 Fahrenheit in Celsius?",
                        "Change 32 degrees Celsius to Fahrenheit",
                        "If it's 75 degrees Fahrenheit, what would that be in Celsius?",
                        "Convert room temperature of 20C to F",
                        "Calculate BMI for someone who is 1.75 meters tall and weighs 70 kg",
                        "What's the BMI for a person weighing 85 kg with height 1.80 meters?",
                        "Can you tell me the BMI if I'm 1.65m tall and weigh 60kg?",
                        "Find BMI for 75kg weight and 1.78m height",
                        "Create me a random number please");

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream and use the prompt itself as the key
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, (KeySelector<String, String>) value -> "orderKey")
                        .apply(new ChatModelIntegrationAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        checkResult(results);
    }

    public void checkResult(CloseableIterator<Object> results) {
        List<String> expectedWords =
                List.of("77", "37", "89", "23", "68", "22", "26", "22", "23", "");
        for (String expected : expectedWords) {
            Assertions.assertTrue(
                    results.hasNext(), "Output messages count %s is less than expected.");
            String res = (String) results.next();
            if (res.contains("error") || res.contains("parameters")) {
                LOG.warn(res);
            } else {
                Assertions.assertTrue(
                        res.contains(expected),
                        String.format(
                                "Groud truth %s is not contained in answer {%s}", expected, res));
            }
        }
    }
}
