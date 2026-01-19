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

import java.util.Map;

import static org.apache.flink.agents.resource.test.CrossLanguageTestPreparationUtils.startMCPServer;

/** Example application that applies {@link MCPCrossLanguageAgent} to a DataStream. */
public class MCPCrossLanguageTest {
    private final Process mcpServerProcess;

    public MCPCrossLanguageTest() {
        this.mcpServerProcess = startMCPServer();
    }

    @Test
    public void testMCPIntegration() throws Exception {
        Assumptions.assumeTrue(mcpServerProcess != null, "MCP Server is not running");

        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Use prompts that utilize the MCP tool and perform prompt checks.
        DataStream<String> inputStream = env.fromData("An input message to invoke the Test Action");

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream and use the prompt itself as the key
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<String, String>) value -> value)
                        .apply(new MCPCrossLanguageAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        checkResult(results);

        mcpServerProcess.destroy();
    }

    @SuppressWarnings("unchecked")
    private void checkResult(CloseableIterator<Object> results) {
        Assertions.assertTrue(
                results.hasNext(), "No output received from VectorStoreIntegrationAgent");

        Object obj = results.next();
        Assertions.assertInstanceOf(Map.class, obj, "Output must be a Map");

        java.util.Map<String, Object> res = (java.util.Map<String, Object>) obj;
        Assertions.assertEquals("PASSED", res.get("test_status"));
    }
}
