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
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end tests for Java async execution functionality.
 *
 * <p>These tests verify that {@code durableExecuteAsync} works correctly for Java actions.
 */
public class AsyncExecutionTest {

    /**
     * Tests that a simple async action works correctly.
     *
     * <p>The agent uses durableExecuteAsync to simulate a slow operation, then accesses memory and
     * sends an event.
     */
    @Test
    public void testSimpleAsyncExecution() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream
        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream =
                env.fromElements(
                        new AsyncExecutionAgent.AsyncRequest(1, "hello"),
                        new AsyncExecutionAgent.AsyncRequest(2, "world"),
                        new AsyncExecutionAgent.AsyncRequest(1, "flink"));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.SimpleAsyncAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        // Verify results
        List<String> outputList = new ArrayList<>();
        while (results.hasNext()) {
            outputList.add(results.next().toString());
        }
        results.close();

        // Should have 3 outputs
        Assertions.assertEquals(3, outputList.size());

        // Each output should contain the async processed result
        for (String output : outputList) {
            Assertions.assertTrue(
                    output.contains("AsyncResult:"),
                    "Output should contain async result: " + output);
            Assertions.assertTrue(
                    output.contains("Processed:"),
                    "Output should contain processed data: " + output);
            Assertions.assertTrue(
                    output.contains("MemoryCheck:"),
                    "Output should contain memory check: " + output);
        }
    }

    /**
     * Tests that multiple executeAsync calls can be chained within a single action.
     *
     * <p>The agent performs three sequential async operations and combines their results.
     */
    @Test
    public void testMultipleAsyncCalls() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream
        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream =
                env.fromElements(
                        new AsyncExecutionAgent.AsyncRequest(1, "test1"),
                        new AsyncExecutionAgent.AsyncRequest(2, "test2"));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.MultiAsyncAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        // Verify results
        List<String> outputList = new ArrayList<>();
        while (results.hasNext()) {
            outputList.add(results.next().toString());
        }
        results.close();

        // Should have 2 outputs
        Assertions.assertEquals(2, outputList.size());

        // Each output should contain all three steps
        for (String output : outputList) {
            Assertions.assertTrue(
                    output.contains("Step1:"), "Output should contain Step1: " + output);
            Assertions.assertTrue(
                    output.contains("Step2:"), "Output should contain Step2: " + output);
            Assertions.assertTrue(
                    output.contains("Step3:"), "Output should contain Step3: " + output);
        }
    }

    /**
     * Tests that async execution works correctly with multiple keys processed concurrently.
     *
     * <p>Different keys should be processed independently with their own async operations.
     */
    @Test
    public void testAsyncWithMultipleKeysHighLoad() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2); // Use parallelism to test concurrent processing

        // Create input DataStream with multiple elements across different keys
        List<AsyncExecutionAgent.AsyncRequest> requests = new ArrayList<>();
        for (int key = 0; key < 5; key++) {
            for (int i = 0; i < 3; i++) {
                requests.add(new AsyncExecutionAgent.AsyncRequest(key, "data-" + key + "-" + i));
            }
        }

        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream = env.fromCollection(requests);

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.SimpleAsyncAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        // Verify results
        List<String> outputList = new ArrayList<>();
        while (results.hasNext()) {
            outputList.add(results.next().toString());
        }
        results.close();

        // Should have 15 outputs (5 keys * 3 elements each)
        Assertions.assertEquals(15, outputList.size());

        // All outputs should be valid
        for (String output : outputList) {
            Assertions.assertTrue(
                    output.contains("AsyncResult:"),
                    "Output should contain async result: " + output);
        }
    }

    /**
     * Tests that async execution on JDK 21+ actually executes tasks in parallel.
     *
     * <p>This test creates multiple tasks that each sleep for a fixed duration. Each async task
     * records its start and end timestamps. We verify parallel execution by checking if the
     * execution time ranges overlap.
     *
     * <p>On JDK 21+: Tasks run in parallel, their execution times overlap On JDK &lt; 21: Tasks run
     * sequentially, no overlap
     */
    @Test
    public void testAsyncExecutionIsActuallyParallel() throws Exception {
        boolean continuationSupported = ContinuationActionExecutor.isContinuationSupported();
        int javaVersion = Runtime.version().feature();

        System.out.println("=== Async Parallelism Test ===");
        System.out.println("Java version: " + javaVersion);
        System.out.println("Continuation supported: " + continuationSupported);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // Single parallelism to test async within one operator

        // Create 3 requests with different keys, each will sleep 500ms
        int numRequests = 3;
        int sleepTimeMs = 500;

        List<AsyncExecutionAgent.AsyncRequest> requests = new ArrayList<>();
        for (int i = 0; i < numRequests; i++) {
            requests.add(
                    new AsyncExecutionAgent.AsyncRequest(i, "parallel-test-" + i, sleepTimeMs));
        }

        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream = env.fromCollection(requests);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Use TimedAsyncAgent which records timestamps
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.TimedAsyncAgent(sleepTimeMs))
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        // Parse execution timestamps from output
        List<long[]> executionRanges = new ArrayList<>();
        while (results.hasNext()) {
            String output = results.next().toString();
            // Parse: TimedAsync[key=X,start=Y,end=Z]
            java.util.regex.Pattern pattern =
                    java.util.regex.Pattern.compile("start=(\\d+),end=(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                executionRanges.add(new long[] {start, end});
                System.out.println("Task execution: start=" + start + ", end=" + end);
            }
        }
        results.close();

        Assertions.assertEquals(numRequests, executionRanges.size());

        // Check for overlap between execution ranges
        // Two ranges [s1, e1] and [s2, e2] overlap if s1 < e2 && s2 < e1
        int overlapCount = 0;
        for (int i = 0; i < executionRanges.size(); i++) {
            for (int j = i + 1; j < executionRanges.size(); j++) {
                long[] range1 = executionRanges.get(i);
                long[] range2 = executionRanges.get(j);
                boolean overlaps = range1[0] < range2[1] && range2[0] < range1[1];
                if (overlaps) {
                    overlapCount++;
                    System.out.println(
                            "Overlap detected: ["
                                    + range1[0]
                                    + ","
                                    + range1[1]
                                    + "] and ["
                                    + range2[0]
                                    + ","
                                    + range2[1]
                                    + "]");
                }
            }
        }

        System.out.println("Total overlapping pairs: " + overlapCount);

        String classLocation =
                ContinuationActionExecutor.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toString();
        System.out.println("Class loaded from: " + classLocation);

        if (continuationSupported && javaVersion >= 21) {
            // On JDK 21+, all tasks should overlap (parallel execution)
            // With 3 tasks, we expect 3 overlapping pairs: (0,1), (0,2), (1,2)
            int expectedOverlaps = (numRequests * (numRequests - 1)) / 2;
            Assertions.assertTrue(
                    overlapCount >= expectedOverlaps - 1, // Allow some tolerance
                    String.format(
                            "On JDK 21+, async tasks should run in parallel (overlapping). "
                                    + "Expected at least %d overlapping pairs, but found %d.",
                            expectedOverlaps - 1, overlapCount));
            System.out.println("✓ Async execution is PARALLEL (as expected on JDK 21+)");
        } else {
            // On JDK < 21, tasks run sequentially - no overlap expected
            Assertions.assertEquals(
                    0,
                    overlapCount,
                    String.format(
                            "On JDK < 21, async tasks should run sequentially (no overlap). "
                                    + "But found %d overlapping pairs.",
                            overlapCount));
            System.out.println("✓ Async execution is SEQUENTIAL (as expected on JDK < 21)");
        }

        System.out.println("=== Test Passed ===");
    }

    /**
     * Tests that durableExecute (sync) works correctly.
     *
     * <p>The agent uses durableExecute to simulate a slow synchronous operation.
     */
    @Test
    public void testDurableExecuteSync() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream
        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream =
                env.fromElements(
                        new AsyncExecutionAgent.AsyncRequest(1, "hello"),
                        new AsyncExecutionAgent.AsyncRequest(2, "world"));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.SyncDurableAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        // Verify results
        List<String> outputList = new ArrayList<>();
        while (results.hasNext()) {
            outputList.add(results.next().toString());
        }
        results.close();

        // Should have 2 outputs
        Assertions.assertEquals(2, outputList.size());

        // Each output should contain the sync processed result
        for (String output : outputList) {
            Assertions.assertTrue(
                    output.contains("SyncDurable["),
                    "Output should contain sync durable result: " + output);
            Assertions.assertTrue(
                    output.contains("SyncProcessed:"),
                    "Output should contain processed data: " + output);
        }
    }
}
