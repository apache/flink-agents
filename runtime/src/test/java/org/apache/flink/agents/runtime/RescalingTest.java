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

package org.apache.flink.agents.runtime;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Deadline;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.SinkFunction;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.testutils.TestingUtils;
import org.apache.flink.testutils.executor.TestExecutorResource;
import org.apache.flink.util.Collector;
import org.apache.flink.util.TestLogger;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.apache.flink.runtime.testutils.CommonTestUtils.waitForAllTaskRunning;
import static org.apache.flink.test.util.TestUtils.submitJobAndWaitForResult;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** This test case is derived from an existing test in Flink. */
@RunWith(Parameterized.class)
public class RescalingTest extends TestLogger {

    @ClassRule
    public static final TestExecutorResource<ScheduledExecutorService> EXECUTOR_RESOURCE =
            TestingUtils.defaultExecutorResource();

    private static final int numTaskManagers = 2;
    private static final int slotsPerTaskManager = 2;
    private static final int numSlots = numTaskManagers * slotsPerTaskManager;

    @Parameterized.Parameters(name = "backend = {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{"hashmap"}, {"rocksdb"}});
    }

    public RescalingTest(String backend) {
        this.backend = backend;
    }

    private final String backend;

    private String currentBackend = null;

    private static MiniClusterWithClientResource cluster;

    @ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setup() throws Exception {
        // detect parameter change
        if (currentBackend != backend) {
            shutDownExistingCluster();

            currentBackend = backend;

            Configuration config = new Configuration();

            final File checkpointDir = temporaryFolder.newFolder();
            final File savepointDir = temporaryFolder.newFolder();

            config.set(StateBackendOptions.STATE_BACKEND, currentBackend);
            config.set(
                    CheckpointingOptions.CHECKPOINTS_DIRECTORY, checkpointDir.toURI().toString());
            config.set(CheckpointingOptions.SAVEPOINT_DIRECTORY, savepointDir.toURI().toString());

            cluster =
                    new MiniClusterWithClientResource(
                            new MiniClusterResourceConfiguration.Builder()
                                    .setConfiguration(config)
                                    .setNumberTaskManagers(numTaskManagers)
                                    .setNumberSlotsPerTaskManager(numSlots)
                                    .build());
            cluster.before();
        }

        TestAgent.numProcessedEvent.set(0);
    }

    @AfterClass
    public static void shutDownExistingCluster() {
        if (cluster != null) {
            cluster.after();
            cluster = null;
        }
    }

    @Test
    public void testSavepointRescalingInKeyedState() throws Exception {
        testSavepointRescalingKeyedState(false, false);
    }

    @Test
    public void testSavepointRescalingOutKeyedState() throws Exception {
        testSavepointRescalingKeyedState(true, false);
    }

    @Test
    public void testSavepointRescalingInKeyedStateDerivedMaxParallelism() throws Exception {
        testSavepointRescalingKeyedState(false, true);
    }

    @Test
    public void testSavepointRescalingOutKeyedStateDerivedMaxParallelism() throws Exception {
        testSavepointRescalingKeyedState(true, true);
    }

    /**
     * Tests that a job with purely keyed state can be restarted from a savepoint with a different
     * parallelism.
     */
    public void testSavepointRescalingKeyedState(boolean scaleOut, boolean deriveMaxParallelism)
            throws Exception {
        final int numberKeys = 42;
        final int numberElements = 1000;
        final int numberElements2 = 500;
        final int parallelism = scaleOut ? numSlots / 2 : numSlots;
        final int parallelism2 = scaleOut ? numSlots : numSlots / 2;
        final int maxParallelism = 13;

        Duration timeout = Duration.ofMinutes(3);
        Deadline deadline = Deadline.now().plus(timeout);

        ClusterClient<?> client = cluster.getClusterClient();

        try {
            JobGraph jobGraph =
                    createJobGraphWithKeyedState(
                            parallelism, maxParallelism, numberKeys, numberElements, false, 100);

            final JobID jobID = jobGraph.getJobID();

            client.submitJob(jobGraph).get();

            // wait til the sources have emitted numberElements for each key and completed a
            // checkpoint
            assertTrue(
                    SubtaskIndexFlatMapper.workCompletedLatch.await(
                            deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS));

            // verify the current state

            Set<Tuple2<Integer, Integer>> actualResult = CollectionSink.getElementsSet();

            Set<Tuple2<Integer, Integer>> expectedResult = new HashSet<>();

            for (int key = 0; key < numberKeys; key++) {
                int keyGroupIndex = KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism);

                expectedResult.add(
                        Tuple2.of(
                                KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                                        maxParallelism, parallelism, keyGroupIndex),
                                numberElements * key));
            }

            assertEquals(expectedResult, actualResult);

            // clear the CollectionSink set for the restarted job
            CollectionSink.clearElementsSet();

            waitForAllTaskRunning(cluster.getMiniCluster(), jobGraph.getJobID(), false);
            CompletableFuture<String> savepointPathFuture =
                    client.triggerSavepoint(jobID, null, SavepointFormatType.CANONICAL);

            final String savepointPath =
                    savepointPathFuture.get(deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS);

            client.cancel(jobID).get();

            while (!getRunningJobs(client).isEmpty()) {
                Thread.sleep(50);
            }

            int restoreMaxParallelism =
                    deriveMaxParallelism ? JobVertex.MAX_PARALLELISM_DEFAULT : maxParallelism;

            JobGraph scaledJobGraph =
                    createJobGraphWithKeyedState(
                            parallelism2,
                            restoreMaxParallelism,
                            numberKeys,
                            numberElements2,
                            true,
                            100);

            scaledJobGraph.setSavepointRestoreSettings(
                    SavepointRestoreSettings.forPath(savepointPath));

            submitJobAndWaitForResult(client, scaledJobGraph, getClass().getClassLoader());

            Set<Tuple2<Integer, Integer>> actualResult2 = CollectionSink.getElementsSet();

            Set<Tuple2<Integer, Integer>> expectedResult2 = new HashSet<>();

            for (int key = 0; key < numberKeys; key++) {
                int keyGroupIndex = KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism);
                expectedResult2.add(
                        Tuple2.of(
                                KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                                        maxParallelism, parallelism2, keyGroupIndex),
                                key * (numberElements + numberElements2)));
            }

            assertEquals(expectedResult2, actualResult2);
            assertEquals(
                    numberKeys * (numberElements + numberElements2) * 2,
                    TestAgent.numProcessedEvent.get());

        } finally {
            // clear the CollectionSink set for the restarted job
            CollectionSink.clearElementsSet();
        }
    }

    private static JobGraph createJobGraphWithKeyedState(
            int parallelism,
            int maxParallelism,
            int numberKeys,
            int numberElements,
            boolean terminateAfterEmission,
            int checkpointingInterval)
            throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(parallelism);
        if (0 < maxParallelism) {
            env.getConfig().setMaxParallelism(maxParallelism);
        }
        env.enableCheckpointing(checkpointingInterval);
        env.configure(new Configuration().set(RestartStrategyOptions.RESTART_STRATEGY, "none"));
        env.getConfig().setUseSnapshotCompression(true);

        DataStream<Integer> input =
                env.addSource(
                                new SubtaskIndexSource(
                                        numberKeys, numberElements, terminateAfterEmission))
                        .keyBy(
                                new KeySelector<Integer, Integer>() {
                                    private static final long serialVersionUID =
                                            -7952298871120320940L;

                                    @Override
                                    public Integer getKey(Integer value) throws Exception {
                                        return value;
                                    }
                                });

        // insert agent topology
        input =
                CompileUtils.connectToAgent(
                                (KeyedStream<Integer, Integer>) input,
                                new AgentPlan(new TestAgent()))
                        .map(
                                new MapFunction<Object, Integer>() {
                                    @Override
                                    public Integer map(Object value) throws Exception {
                                        return (Integer) value;
                                    }
                                })
                        .keyBy(
                                new KeySelector<Integer, Integer>() {
                                    private static final long serialVersionUID =
                                            -7952298871120320940L;

                                    @Override
                                    public Integer getKey(Integer value) throws Exception {
                                        return value;
                                    }
                                });

        SubtaskIndexFlatMapper.workCompletedLatch = new CountDownLatch(numberKeys);

        DataStream<Tuple2<Integer, Integer>> result =
                input.flatMap(new SubtaskIndexFlatMapper(numberElements));

        result.addSink(new CollectionSink<Tuple2<Integer, Integer>>());

        return env.getStreamGraph().getJobGraph();
    }

    private static class SubtaskIndexSource extends RichParallelSourceFunction<Integer> {

        private static final long serialVersionUID = -400066323594122516L;

        private final int numberKeys;
        private final int numberElements;
        private final boolean terminateAfterEmission;

        protected int counter = 0;

        private boolean running = true;

        SubtaskIndexSource(int numberKeys, int numberElements, boolean terminateAfterEmission) {

            this.numberKeys = numberKeys;
            this.numberElements = numberElements;
            this.terminateAfterEmission = terminateAfterEmission;
        }

        @Override
        public void run(SourceContext<Integer> ctx) throws Exception {
            final Object lock = ctx.getCheckpointLock();
            final int subtaskIndex = getRuntimeContext().getTaskInfo().getIndexOfThisSubtask();

            while (running) {

                if (counter < numberElements) {
                    synchronized (lock) {
                        for (int value = subtaskIndex;
                                value < numberKeys;
                                value +=
                                        getRuntimeContext()
                                                .getTaskInfo()
                                                .getNumberOfParallelSubtasks()) {

                            ctx.collect(value);
                        }

                        counter++;
                    }
                } else {
                    if (terminateAfterEmission) {
                        running = false;
                    } else {
                        Thread.sleep(100);
                    }
                }
            }
        }

        @Override
        public void cancel() {
            running = false;
        }
    }

    private static class SubtaskIndexFlatMapper
            extends RichFlatMapFunction<Integer, Tuple2<Integer, Integer>>
            implements CheckpointedFunction {

        private static final long serialVersionUID = 5273172591283191348L;

        private static CountDownLatch workCompletedLatch = new CountDownLatch(1);

        private transient ValueState<Integer> counter;
        private transient ValueState<Integer> sum;

        private final int numberElements;

        SubtaskIndexFlatMapper(int numberElements) {
            this.numberElements = numberElements;
        }

        @Override
        public void flatMap(Integer value, Collector<Tuple2<Integer, Integer>> out)
                throws Exception {
            int count = counter.value() + 1;
            counter.update(count);

            int s = sum.value() + value;
            sum.update(s);

            if (count % numberElements == 0) {
                out.collect(
                        Tuple2.of(getRuntimeContext().getTaskInfo().getIndexOfThisSubtask(), s));
                workCompletedLatch.countDown();
            }
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            // all managed, nothing to do.
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            counter =
                    context.getKeyedStateStore()
                            .getState(new ValueStateDescriptor<>("counter", Integer.class, 0));
            sum =
                    context.getKeyedStateStore()
                            .getState(new ValueStateDescriptor<>("sum", Integer.class, 0));
        }
    }

    private static class CollectionSink<IN> implements SinkFunction<IN> {

        private static Set<Object> elements =
                Collections.newSetFromMap(new ConcurrentHashMap<Object, Boolean>());

        private static final long serialVersionUID = -1652452958040267745L;

        public static <IN> Set<IN> getElementsSet() {
            return (Set<IN>) elements;
        }

        public static void clearElementsSet() {
            elements.clear();
        }

        @Override
        public void invoke(IN value) throws Exception {
            elements.add(value);
        }
    }

    private static List<JobID> getRunningJobs(ClusterClient<?> client) throws Exception {
        Collection<JobStatusMessage> statusMessages = client.listJobs().get();
        return statusMessages.stream()
                .filter(status -> !status.getJobState().isGloballyTerminalState())
                .map(JobStatusMessage::getJobId)
                .collect(Collectors.toList());
    }

    /** Test event class for testing. */
    public static class TestEvent extends Event {
        private final int data;

        public TestEvent(int data) {
            this.data = data;
        }

        public int getData() {
            return data;
        }
    }

    /** Test agent class for testing. */
    public static class TestAgent extends Agent {

        public static final AtomicInteger numProcessedEvent = new AtomicInteger(0);

        @Action(listenEvents = {InputEvent.class})
        public static void handleInputEvent(InputEvent event, RunnerContext context) {
            // Test action implementation
            numProcessedEvent.incrementAndGet();
            context.sendEvent(new TestEvent((Integer) event.getInput()));
        }

        @Action(listenEvents = {TestEvent.class})
        public static void handleTestEvent(TestEvent event, RunnerContext context) {
            numProcessedEvent.incrementAndGet();
            context.sendEvent(new OutputEvent(event.data));
        }
    }
}
