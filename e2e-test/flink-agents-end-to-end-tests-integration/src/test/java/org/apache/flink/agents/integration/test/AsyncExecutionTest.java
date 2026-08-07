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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Test
    public void testToolCallBatchExecutionIsActuallyParallel() throws Exception {
        boolean continuationSupported = ContinuationActionExecutor.isContinuationSupported();
        int javaVersion = Runtime.version().feature();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        int sleepTimeMs = 500;
        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream =
                env.fromElements(
                        new AsyncExecutionAgent.AsyncRequest(1, "tool-batch", sleepTimeMs));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, true);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 3);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.ToolBatchAgent(sleepTimeMs))
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<long[]> executionRanges = new ArrayList<>();
        while (results.hasNext()) {
            String output = results.next().toString();
            Matcher matcher = Pattern.compile("start=(\\d+),end=(\\d+)").matcher(output);
            while (matcher.find()) {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                executionRanges.add(new long[] {start, end});
            }
        }
        results.close();

        Assertions.assertEquals(3, executionRanges.size());
        int overlapCount = countOverlaps(executionRanges);
        if (continuationSupported && javaVersion >= 21) {
            Assertions.assertTrue(
                    overlapCount >= 2,
                    "On JDK 21+, tool calls in one ToolRequestEvent should run in parallel.");
        } else {
            Assertions.assertEquals(
                    0,
                    overlapCount,
                    "On JDK < 21, tool-call batch execution should use the sequential fallback.");
        }
    }

    @Test
    public void testToolCallBatchRespectsMaxParallelismInFlight() throws Exception {
        boolean continuationSupported = ContinuationActionExecutor.isContinuationSupported();
        int javaVersion = Runtime.version().feature();
        if (!continuationSupported || javaVersion < 21) {
            System.out.println(
                    "Skipping max-parallelism e2e: requires JDK 21+ Continuation execution");
            return;
        }

        final int sleepTimeMs = AsyncExecutionAgent.ToolBatchMaxParallelismAgent.SLEEP_MS;
        final int toolCount = AsyncExecutionAgent.ToolBatchMaxParallelismAgent.TOOL_COUNT;
        final int maxParallelism = 2;

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream =
                env.fromElements(
                        new AsyncExecutionAgent.AsyncRequest(1, "tool-batch-max-parallelism"));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, true);
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 8);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, maxParallelism);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.ToolBatchMaxParallelismAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        long pipelineStart = System.currentTimeMillis();
        agentsEnv.execute();
        long pipelineEnd = System.currentTimeMillis();

        List<long[]> executionRanges = new ArrayList<>();
        while (results.hasNext()) {
            String output = results.next().toString();
            Matcher matcher = Pattern.compile("start=(\\d+),end=(\\d+)").matcher(output);
            while (matcher.find()) {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                executionRanges.add(new long[] {start, end});
            }
        }
        results.close();

        Assertions.assertEquals(
                toolCount,
                executionRanges.size(),
                "Expected one timing record per tool call in the batch.");
        int maxConcurrent = maxConcurrentOverlap(executionRanges);
        Assertions.assertTrue(
                maxConcurrent <= maxParallelism,
                "At most "
                        + maxParallelism
                        + " tool calls should run concurrently, but observed "
                        + maxConcurrent
                        + " in-flight.");

        long batchStart =
                executionRanges.stream().mapToLong(range -> range[0]).min().orElse(pipelineStart);
        long batchEnd =
                executionRanges.stream().mapToLong(range -> range[1]).max().orElse(pipelineEnd);
        long batchDuration = batchEnd - batchStart;
        int expectedWaves = (int) Math.ceil((double) toolCount / maxParallelism);
        long expectedMinDuration = (long) sleepTimeMs * expectedWaves;
        Assertions.assertTrue(
                batchDuration >= expectedMinDuration - 150,
                "With max-parallelism="
                        + maxParallelism
                        + ", a "
                        + toolCount
                        + "-tool batch should take at least "
                        + expectedWaves
                        + " waves (~"
                        + expectedMinDuration
                        + "ms), but took "
                        + batchDuration
                        + "ms.");
        Assertions.assertTrue(
                batchDuration < (long) sleepTimeMs * toolCount - 150,
                "Capped batch should finish faster than fully serial execution: "
                        + batchDuration
                        + "ms vs serial ~"
                        + ((long) sleepTimeMs * toolCount)
                        + "ms.");
        Assertions.assertTrue(
                pipelineEnd - pipelineStart >= expectedMinDuration - 150,
                "Pipeline wall time should reflect capped in-flight execution.");
    }

    @Test
    public void testToolCallBatchTimeoutKeepsCompletedOutcomes() throws Exception {
        boolean continuationSupported = ContinuationActionExecutor.isContinuationSupported();
        int javaVersion = Runtime.version().feature();
        if (!continuationSupported || javaVersion < 21) {
            System.out.println(
                    "Skipping batch timeout e2e: requires JDK 21+ Continuation execution");
            return;
        }

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<AsyncExecutionAgent.AsyncRequest> inputStream =
                env.fromElements(new AsyncExecutionAgent.AsyncRequest(1, "tool-batch-timeout"));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, true);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 2);
        agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_BATCH_TIMEOUT_MS, 200L);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new AsyncExecutionAgent.AsyncRequestKeySelector())
                        .apply(new AsyncExecutionAgent.ToolBatchTimeoutAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> outputList = new ArrayList<>();
        while (results.hasNext()) {
            outputList.add(results.next().toString());
        }
        results.close();

        Assertions.assertEquals(1, outputList.size());
        String output = outputList.get(0);

        Pattern fastToolPattern =
                Pattern.compile("call=1.*sleep_ms=0.*start=\\d+,end=\\d+", Pattern.DOTALL);
        Assertions.assertTrue(
                fastToolPattern.matcher(output).find(),
                "Fast tool should complete before the batch deadline: " + output);
        Assertions.assertTrue(
                output.contains("execute failed") || output.toLowerCase().contains("timed out"),
                "Slow tool should fail when the batch deadline elapses: " + output);
        Assertions.assertFalse(
                Pattern.compile("call=2.*sleep_ms=800.*start=\\d+,end=\\d+", Pattern.DOTALL)
                        .matcher(output)
                        .find(),
                "Slow tool should not report a successful timed result: " + output);
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

    private static int maxConcurrentOverlap(List<long[]> executionRanges) {
        List<long[]> events = new ArrayList<>();
        for (long[] range : executionRanges) {
            events.add(new long[] {range[0], 1});
            events.add(new long[] {range[1], -1});
        }
        events.sort(
                (left, right) -> {
                    int byTime = Long.compare(left[0], right[0]);
                    if (byTime != 0) {
                        return byTime;
                    }
                    return Long.compare(left[1], right[1]);
                });
        int current = 0;
        int max = 0;
        for (long[] event : events) {
            current += event[1];
            max = Math.max(max, current);
        }
        return max;
    }

    private static int countOverlaps(List<long[]> executionRanges) {
        int overlapCount = 0;
        for (int i = 0; i < executionRanges.size(); i++) {
            for (int j = i + 1; j < executionRanges.size(); j++) {
                long[] range1 = executionRanges.get(i);
                long[] range2 = executionRanges.get(j);
                if (range1[0] < range2[1] && range2[0] < range1[1]) {
                    overlapCount++;
                }
            }
        }
        return overlapCount;
    }
}
