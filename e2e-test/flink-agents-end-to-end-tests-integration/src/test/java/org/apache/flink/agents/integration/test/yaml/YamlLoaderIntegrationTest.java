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

package org.apache.flink.agents.integration.test.yaml;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.integration.test.OllamaPreparationUtils;
import org.apache.flink.agents.integration.test.yaml.YamlChatActions.YamlChatInput;
import org.apache.flink.agents.integration.test.yaml.YamlChatActions.YamlChatOutput;
import org.apache.flink.api.common.typeinfo.TypeInformation;
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
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * End-to-end tests for the Java YAML loader. Each test loads a YAML file via {@link
 * AgentsExecutionEnvironment#loadYaml(Path...)}, dispatches the declared agent(s) by name through
 * the Flink runner, and asserts on the LLM outputs.
 *
 * <p>Gated on a local Ollama serving {@code qwen3:1.7b}.
 */
public class YamlLoaderIntegrationTest extends OllamaPreparationUtils {

    private static final Logger LOG = LoggerFactory.getLogger(YamlLoaderIntegrationTest.class);
    private static final String OLLAMA_MODEL = "qwen3:1.7b";

    private final boolean ollamaReady;

    public YamlLoaderIntegrationTest() throws IOException {
        ollamaReady = pullModel(OLLAMA_MODEL);
    }

    /**
     * Single agent loaded from {@code yaml_test_agent.yaml} and applied by name: math path goes
     * through the {@code add} tool; creative path is a plain chat call. Asserts the math digit
     * survives and the creative answer mentions cats.
     */
    @Test
    public void testSingleYamlAgent() throws Exception {
        Assumptions.assumeTrue(ollamaReady, "Ollama unavailable; skipping YAML e2e test.");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<YamlChatInput> inputStream =
                env.fromData(
                                new YamlChatInput(1, "calculate the sum of 1 and 2."),
                                new YamlChatInput(2, "Tell me a joke about cats."))
                        .returns(TypeInformation.of(YamlChatInput.class));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.loadYaml(yamlFixture("yaml_test_agent.yaml"));

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, (KeySelector<YamlChatInput, Integer>) v -> v.id)
                        .apply("yaml_test_agent")
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        Map<Integer, String> answers = collectAnswers(results);

        String mathAnswer = answers.get(1);
        Assertions.assertNotNull(mathAnswer, "math answer missing");
        Assertions.assertTrue(
                mathAnswer.contains("3"), String.format("math answer missing '3': %s", mathAnswer));

        String creativeAnswer = answers.get(2);
        Assertions.assertNotNull(creativeAnswer, "creative answer missing");
        Assertions.assertTrue(
                creativeAnswer.toLowerCase().contains("cat"),
                String.format("creative answer missing 'cat': %s", creativeAnswer));
    }

    /**
     * Two agents declared in one YAML file, chained as a single Flink pipeline:
     *
     * <pre>{@code
     * fromData → math_agent → commentator_agent → collect
     * }</pre>
     *
     * <p>Both agents reuse a file-level {@code ollama_connection} and the file-level {@code
     * process_chat_response} action. The test exercises chaining two YAML-loaded agents, proves the
     * file-level shared connection + shared action are reusable across both agents, and asserts the
     * math digit survives the second LLM hop.
     */
    @Test
    public void testChainedYamlAgents() throws Exception {
        Assumptions.assumeTrue(ollamaReady, "Ollama unavailable; skipping YAML e2e test.");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.loadYaml(yamlFixture("yaml_multi_agent.yaml"));

        DataStream<YamlChatInput> inputStream =
                env.fromData(new YamlChatInput(1, "calculate the sum of 1 and 2."))
                        .returns(TypeInformation.of(YamlChatInput.class));

        DataStream<Object> mathOutput =
                agentsEnv
                        .fromDataStream(
                                inputStream, (KeySelector<YamlChatInput, Integer>) v -> v.id)
                        .apply("math_agent")
                        .toDataStream();

        DataStream<YamlChatOutput> mathOutputTyped =
                mathOutput
                        .map(o -> (YamlChatOutput) o)
                        .returns(TypeInformation.of(YamlChatOutput.class));

        DataStream<Object> finalOutput =
                agentsEnv
                        .fromDataStream(
                                mathOutputTyped, (KeySelector<YamlChatOutput, Integer>) v -> v.id)
                        .apply("commentator_agent")
                        .toDataStream();

        CloseableIterator<Object> results = finalOutput.collectAsync();
        agentsEnv.execute();

        Map<Integer, String> answers = collectAnswers(results);
        String finalAnswer = answers.get(1);
        Assertions.assertNotNull(finalAnswer, "final answer missing from chained output");
        Assertions.assertTrue(
                finalAnswer.contains("3"),
                String.format("math result missing from chained output: %s", finalAnswer));
    }

    private static Path yamlFixture(String name) {
        URL resource = YamlLoaderIntegrationTest.class.getClassLoader().getResource("yaml/" + name);
        Objects.requireNonNull(resource, "fixture not found on classpath: yaml/" + name);
        return Paths.get(resource.getPath());
    }

    /** Collect {@code {id: answer}} from a finished output stream of {@link YamlChatOutput}. */
    private static Map<Integer, String> collectAnswers(CloseableIterator<Object> results) {
        Map<Integer, String> answers = new HashMap<>();
        List<Object> raw = new ArrayList<>();
        while (results.hasNext()) {
            Object next = results.next();
            raw.add(next);
            if (next instanceof YamlChatOutput) {
                YamlChatOutput output = (YamlChatOutput) next;
                answers.put(output.id, output.answer);
            }
        }
        LOG.info("Collected {} raw results: {}", raw.size(), raw);
        return answers;
    }
}
