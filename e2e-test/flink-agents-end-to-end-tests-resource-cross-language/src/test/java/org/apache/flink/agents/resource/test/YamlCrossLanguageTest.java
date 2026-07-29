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
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.apache.flink.agents.resource.test.ChatModelCrossLanguageAgent.OLLAMA_MODEL;
import static org.apache.flink.agents.resource.test.CrossLanguageTestPreparationUtils.pullModel;

/**
 * End-to-end test for the Java YAML loader in a cross-language setting: a single YAML file declares
 * a Python-wrapped chat model that uses a Java function tool ({@code calculateBMI}) and a
 * Java-native chat model with no tools. The Java host loads it via {@link
 * AgentsExecutionEnvironment#loadYaml(Path...)} and dispatches the agent by name.
 *
 * <p>Math input exercises the Python→Java tool bridge originating from a Java loader entry;
 * creative input exercises the same YAML mixing Python-wrapped and Java-native chat models on a
 * Java host.
 */
public class YamlCrossLanguageTest {

    private static final Logger LOG = LoggerFactory.getLogger(YamlCrossLanguageTest.class);

    private final boolean ollamaReady;

    public YamlCrossLanguageTest() throws IOException {
        ollamaReady = pullModel(OLLAMA_MODEL);
    }

    @Test
    public void testYamlCrossLanguageAgent() throws Exception {
        Assumptions.assumeTrue(ollamaReady, "Ollama Server information is not provided");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<String> inputStream =
                env.fromData(
                        "Calculate BMI for someone who is 1.75 meters tall and weighs 70 kg",
                        "Tell me a joke about cats.");

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.loadYaml(yamlFixture("yaml_cross_language_agent.yaml"));

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, (KeySelector<String, String>) value -> "orderKey")
                        .apply("yaml_cross_language_agent")
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> responses = new ArrayList<>();
        while (results.hasNext()) {
            responses.add(String.valueOf(results.next()));
        }
        LOG.info("Cross-language YAML agent responses: {}", responses);

        Assertions.assertEquals(
                2, responses.size(), "expected 2 responses, got " + responses.size());

        String joined = String.join("\n", responses).toLowerCase();
        Assertions.assertTrue(
                joined.contains("22"), String.format("math answer missing '22': %s", responses));
        Assertions.assertTrue(
                joined.contains("cat"),
                String.format("creative answer missing 'cat': %s", responses));
    }

    private static Path yamlFixture(String name) {
        URL resource = YamlCrossLanguageTest.class.getClassLoader().getResource("yaml/" + name);
        Objects.requireNonNull(resource, "fixture not found on classpath: yaml/" + name);
        return Paths.get(resource.getPath());
    }
}
