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
import org.apache.flink.agents.integration.test.ToolParameterInjectionAgent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** End-to-end tests for YAML-declared tool parameter injection. */
public class YamlToolParameterInjectionIntegrationTest {

    /**
     * YAML {@code injected_args} hides {@code tenant_id} from schema and injects it at call time.
     */
    @Test
    public void testYamlToolParameterInjectionAgent() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<String> inputStream = env.fromData("order-1", "order-2");

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().setStr("tenant_id", "tenant-yaml");
        agentsEnv.loadYaml(yamlFixture("yaml_tool_parameter_injection_agent.yaml"));

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new ToolParameterInjectionAgent.OrderKeySelector())
                        .apply("yaml_tool_parameter_injection_agent")
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> actual = new ArrayList<>();
        while (results.hasNext()) {
            actual.add(String.valueOf(results.next()));
        }
        actual.sort(String::compareTo);

        Assertions.assertEquals(
                List.of("yaml-checked:tenant-yaml:order-1", "yaml-checked:tenant-yaml:order-2"),
                actual);
    }

    private static Path yamlFixture(String name) {
        URL resource =
                YamlToolParameterInjectionIntegrationTest.class
                        .getClassLoader()
                        .getResource("yaml/" + name);
        Objects.requireNonNull(resource, "fixture not found on classpath: yaml/" + name);
        return Paths.get(resource.getPath());
    }
}
