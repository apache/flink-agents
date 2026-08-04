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

package org.apache.flink.agents.integration.test.subagent;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * End-to-end tests for external sub-agents on a real Flink job: registration, invocation, failure
 * reporting via Result, and YAML-declared registration.
 */
public class ExternalSubagentTest {

    @Test
    public void javaExternalSubagentCallEndToEnd() throws Exception {
        List<String> outputs =
                runJob(new ExternalSubagentAgent(new MockExternalSubagentSetup("http://ext:8080")));

        Assertions.assertEquals(2, outputs.size());
        for (long input : new long[] {1L, 2L}) {
            Assertions.assertTrue(
                    outputs.contains("HTTP response for: " + input + " from http://ext:8080"),
                    "missing output for input " + input + ": " + outputs);
        }
    }

    @Test
    public void javaExternalSubagentFailureSurfacesViaResult() throws Exception {
        List<String> outputs =
                runJob(
                        new ExternalSubagentAgent(
                                new MockExternalSubagentSetup("http://down:8080", true)));

        Assertions.assertEquals(2, outputs.size());
        for (String output : outputs) {
            // errorMessage is the full stack trace, which contains the original message.
            Assertions.assertTrue(
                    output.startsWith("error:")
                            && output.contains("endpoint http://down:8080 is down"),
                    "unexpected failure output: " + output);
        }
    }

    @Test
    public void yamlExternalSubagentCallEndToEnd() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<Long> inputStream = env.fromData(1L, 2L);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.loadYaml(yamlFixture("external_subagent_agent.yaml"));
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new ExternalSubagentAgent.LongKeySelector())
                        .apply("external_subagent_yaml_agent")
                        .toDataStream();

        List<String> outputs = collect(outputStream, agentsEnv);

        Assertions.assertEquals(2, outputs.size());
        for (long input : new long[] {1L, 2L}) {
            Assertions.assertTrue(
                    outputs.contains(
                            "HTTP response for: " + input + " from http://yaml-endpoint:8080"),
                    "missing output for input " + input + ": " + outputs);
        }
    }

    private static List<String> runJob(ExternalSubagentAgent agent) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<Long> inputStream = env.fromData(1L, 2L);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new ExternalSubagentAgent.LongKeySelector())
                        .apply(agent)
                        .toDataStream();

        return collect(outputStream, agentsEnv);
    }

    private static List<String> collect(
            DataStream<Object> outputStream, AgentsExecutionEnvironment agentsEnv)
            throws Exception {
        try (CloseableIterator<Object> results = outputStream.collectAsync()) {
            agentsEnv.execute();
            List<String> outputs = new ArrayList<>();
            while (results.hasNext()) {
                outputs.add(results.next().toString());
            }
            return outputs;
        }
    }

    private static java.nio.file.Path yamlFixture(String fileName) {
        URL url =
                Objects.requireNonNull(
                        ExternalSubagentTest.class.getClassLoader().getResource("yaml/" + fileName),
                        "missing yaml fixture: " + fileName);
        return Paths.get(url.getPath());
    }
}
