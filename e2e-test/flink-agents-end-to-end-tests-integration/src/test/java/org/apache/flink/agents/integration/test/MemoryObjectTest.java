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
import org.junit.jupiter.api.Test;

/**
 * Example to test MemoryObject in a complete Java Flink execution environment. This job triggers
 * the {@link MemoryObjectAgent} to test storing and retrieving complex data structures.
 */
public class MemoryObjectTest {

    @Test
    public void testMemoryObject() throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Use two different keys (1 and 2) to show that memory is isolated per key.
        DataStream<Integer> inputStream = env.fromElements(1, 2, 3, 1);

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream and use the integer itself as the key
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<Integer, Integer>) value -> value)
                        .apply(new MemoryObjectAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();
    }

    private void checkResult(CloseableIterator<Object> results) {
        for (int i = 0; i < 4; i++) {
            Assertions.assertTrue(
                    results.hasNext(),
                    String.format("Output messages count %s is less than expected 4.", i));
            String res = (String) results.next();
            Assertions.assertTrue(res.contains("All assertions passed"));
        }
    }
}
