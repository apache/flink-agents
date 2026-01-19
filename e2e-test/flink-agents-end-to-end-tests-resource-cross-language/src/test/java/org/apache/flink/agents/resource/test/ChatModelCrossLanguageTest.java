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

package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.agents.resource.test.ChatModelCrossLanguageAgent.OLLAMA_MODEL;
import static org.apache.flink.agents.resource.test.CrossLanguageTestPreparationUtils.pullModel;

public class ChatModelCrossLanguageTest {
    private static final Logger LOG = LoggerFactory.getLogger(ChatModelCrossLanguageTest.class);

    private final boolean ollamaReady;

    public ChatModelCrossLanguageTest() throws IOException {
        ollamaReady = pullModel(OLLAMA_MODEL);
    }

    @Test
    public void testChatModeIntegration() throws Exception {
        Assumptions.assumeTrue(ollamaReady, "Ollama Server information is not provided");

        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Use prompts that trigger different tool calls in the agent
        DataStream<String> inputStream =
                env.fromData(
                        "Convert 25 degrees Celsius to Fahrenheit",
                        "Calculate BMI for someone who is 1.75 meters tall and weighs 70 kg",
                        "Create me a random number please");

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream and use the prompt itself as the key
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, (KeySelector<String, String>) value -> "orderKey")
                        .apply(new ChatModelCrossLanguageAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        checkResult(results);
    }

    public void checkResult(CloseableIterator<Object> results) {
        List<String> expectedWords = List.of("77", "22", "");
        List<String> responses = new ArrayList<>();
        while (results.hasNext()) {
            responses.add((String) results.next());
        }

        Assertions.assertEquals(
                expectedWords.size(),
                responses.size(),
                String.format(
                        "LLM response count is mismatch," + "the responses are %s", responses));

        String text = String.join("\n", responses);
        for (String expected : expectedWords) {
            Assertions.assertTrue(
                    text.contains(expected),
                    String.format(
                            "Groud truth %s is not contained in answer {%s}", expected, text));
        }
    }
}
