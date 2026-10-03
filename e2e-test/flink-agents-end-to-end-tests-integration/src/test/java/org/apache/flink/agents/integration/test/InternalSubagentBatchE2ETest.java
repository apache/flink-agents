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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateKeyEncoder;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.SinkFunction;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MiniCluster coverage of internal batches, including a checkpoint-triggered restart. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(120)
public class InternalSubagentBatchE2ETest {

    private static final Map<Integer, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();
    private static final List<String> OUTPUTS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();
    private static final AtomicInteger RESTORES = new AtomicInteger();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static volatile CountDownLatch FIRST_WAVE;
    private static volatile CountDownLatch INTERRUPTED_WORK;
    private static volatile boolean recovery;
    private static volatile int failureIndex;

    @BeforeEach
    void reset() {
        INVOCATIONS.clear();
        OUTPUTS.clear();
        Journal.STATES.clear();
        ACTIVE.set(0);
        MAX_ACTIVE.set(0);
        RESTORES.set(0);
        FAILED.set(false);
        FIRST_WAVE = new CountDownLatch(2);
        INTERRUPTED_WORK = new CountDownLatch(1);
        recovery = false;
        failureIndex = -1;
    }

    @Test
    void differentChildrenKeepOrderedPartialFailureWithOneAsyncWorker() throws Exception {
        failureIndex = 1;
        run();
        assertThat(OUTPUTS).hasSize(1);
        assertThat(OUTPUTS.get(0))
                .startsWith("ok:0|error:")
                .contains("refused:1")
                .endsWith("|ok:2|ok:3");
        assertThat(MAX_ACTIVE.get()).isEqualTo(2);
        assertThat(INVOCATIONS).hasSize(4);
        INVOCATIONS.values().forEach(count -> assertThat(count.get()).isEqualTo(1));
    }

    @Test
    void checkpointRestartsCompletedRunningAndQueuedCalls() throws Exception {
        recovery = true;
        try {
            run();
        } finally {
            INTERRUPTED_WORK.countDown();
        }
        assertThat(FAILED).isTrue();
        assertThat(RESTORES.get()).isEqualTo(1);
        assertThat(OUTPUTS).containsExactly("ok:0|ok:1|ok:2|ok:3");
        assertThat(INVOCATIONS.get(0).get())
                .as("completed child is not executed again")
                .isEqualTo(1);
        assertThat(INVOCATIONS.get(3).get())
                .as("queued child starts only after recovery")
                .isEqualTo(1);
        assertThat(INVOCATIONS.get(1).get())
                .as("running child is resumed")
                .isGreaterThanOrEqualTo(2);
        assertThat(INVOCATIONS.get(2).get())
                .as("admitted child is resumed")
                .isGreaterThanOrEqualTo(2);
    }

    private static void run() throws Exception {
        Configuration flinkConfig = new Configuration();
        flinkConfig.setString("restart-strategy.type", recovery ? "fixed-delay" : "disable");
        flinkConfig.setString("restart-strategy.fixed-delay.attempts", "1");
        flinkConfig.setString("restart-strategy.fixed-delay.delay", "0ms");
        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        env.setParallelism(1);
        if (recovery) {
            env.enableCheckpointing(100);
            env.getCheckpointConfig().setCheckpointTimeout(30000);
        }
        Agent parent = new Agent();
        parent.addResource("first", ResourceType.AGENT, new ChildAgent());
        parent.addResource("second", ResourceType.AGENT, new ChildAgent());
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentBatchE2ETest.class.getMethod(
                        "callBatch", Event.class, RunnerContext.class));
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);
        config.set(AgentExecutionOptions.SUBAGENT_PARALLELISM, 2);
        DataStream<Long> input =
                recovery ? env.addSource(new RestartingSource()) : env.fromElements(1L);
        input.keyBy(value -> value)
                .transform(
                        "internal-batch",
                        TypeInformation.of(Object.class),
                        new JournalOperatorFactory(new AgentPlan(parent, config)))
                .setMaxParallelism(128)
                .addSink(new ResultSink());
        env.execute("internal-subagent-batch");
    }

    public static void callBatch(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup first = (SubagentSetup) ctx.getResource("first", ResourceType.AGENT);
        SubagentSetup second = (SubagentSetup) ctx.getResource("second", ResourceType.AGENT);
        SubagentFuture zero = first.submit(ctx, "0");
        List<SubagentResult> results =
                zero.combine(
                                second.submit(ctx, "1"),
                                first.submit(ctx, "2"),
                                second.submit(ctx, "3"))
                        .awaitAll();
        ctx.sendEvent(
                new OutputEvent(
                        results.stream()
                                .map(
                                        result ->
                                                result.isSuccess()
                                                        ? String.valueOf(
                                                                ((List<?>) result.getResult())
                                                                        .get(0))
                                                        : "error:" + result.getErrorMessage())
                                .collect(Collectors.joining("|"))));
    }

    /**
     * Work suspends through the real durable async path; the parent wait cannot occupy its worker.
     */
    public static class ChildAgent extends Agent {
        public ChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    ChildAgent.class.getMethod("work", Event.class, RunnerContext.class));
        }

        public static void work(Event event, RunnerContext ctx) throws Exception {
            int index = Integer.parseInt(String.valueOf(InputEvent.fromEvent(event).getInput()));
            INVOCATIONS.computeIfAbsent(index, ignored -> new AtomicInteger()).incrementAndGet();
            MAX_ACTIVE.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
            FIRST_WAVE.countDown();
            boolean beforeRestart = RESTORES.get() == 0;
            try {
                String value =
                        ctx.durableExecuteAsync(
                                        new DurableCallable<String>() {
                                            @Override
                                            public String getId() {
                                                return "work:" + index;
                                            }

                                            @Override
                                            public Class<String> getResultClass() {
                                                return String.class;
                                            }

                                            @Override
                                            public String call() throws Exception {
                                                if (recovery && beforeRestart && index != 0) {
                                                    if (!INTERRUPTED_WORK.await(
                                                            60, TimeUnit.SECONDS)) {
                                                        throw new IllegalStateException(
                                                                "checkpoint did not restart parked work");
                                                    }
                                                } else if (!recovery
                                                        && !FIRST_WAVE.await(
                                                                10, TimeUnit.SECONDS)) {
                                                    throw new IllegalStateException(
                                                            "batch did not overlap child actions");
                                                }
                                                return "ok:" + index;
                                            }
                                        })
                                .await();
                if (index == failureIndex) {
                    throw new IllegalStateException("refused:" + index);
                }
                ctx.sendEvent(new OutputEvent(value));
            } finally {
                ACTIVE.decrementAndGet();
            }
        }
    }

    private static final class ResultSink implements SinkFunction<Object> {
        @Override
        public void invoke(Object value, Context context) {
            OUTPUTS.add(String.valueOf(value));
        }
    }

    /** Remains unbounded until the result arrives, allowing barriers through a parked batch. */
    private static final class RestartingSource extends RichParallelSourceFunction<Long>
            implements CheckpointedFunction, CheckpointListener {
        private transient ListState<Boolean> emittedState;
        private boolean emitted;
        private volatile boolean running = true;
        private long eligibleCheckpoint = Long.MAX_VALUE;

        @Override
        public void run(SourceContext<Long> context) throws Exception {
            synchronized (context.getCheckpointLock()) {
                if (!emitted) {
                    context.collect(1L);
                    emitted = true;
                }
            }
            while (running && OUTPUTS.isEmpty()) {
                Thread.sleep(10);
            }
        }

        @Override
        public void cancel() {
            running = false;
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            emittedState.update(Collections.singletonList(emitted));
            if (!FAILED.get() && INVOCATIONS.containsKey(2) && ACTIVE.get() == 2) {
                assertThat(INVOCATIONS).doesNotContainKey(3);
                assertThat(ACTIVE.get()).isEqualTo(2);
                eligibleCheckpoint = context.getCheckpointId();
            }
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            emittedState =
                    context.getOperatorStateStore()
                            .getListState(new ListStateDescriptor<>("emitted", Boolean.class));
            for (boolean value : emittedState.get()) {
                emitted = value;
            }
            if (context.isRestored()) {
                RESTORES.incrementAndGet();
            }
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            if (checkpointId >= eligibleCheckpoint && FAILED.compareAndSet(false, true)) {
                throw new IllegalStateException("restart after batch checkpoint");
            }
        }
    }

    private static final class JournalOperatorFactory
            extends ActionExecutionOperatorFactory<Long, Object> {
        private JournalOperatorFactory(AgentPlan plan) {
            super(plan, true, new Journal());
        }
    }

    /**
     * Same-JVM durable test journal: every access crosses the production serialization boundary. It
     * survives the real task restart, without depending on an external Kafka or Fluss service.
     */
    private static final class Journal implements ActionStateStore, Serializable {
        private static final Map<String, byte[]> STATES = new ConcurrentHashMap<>();

        private String id(Object key, long sequence, Action action, Event event) throws Exception {
            return new ActionStateKeyEncoder(128, LongSerializer.INSTANCE)
                    .generateKey(key, sequence, action, event);
        }

        @Override
        public void put(Object key, long sequence, Action action, Event event, ActionState state)
                throws Exception {
            STATES.put(id(key, sequence, action, event), ActionStateSerde.serialize(state));
        }

        @Override
        public ActionState get(Object key, long sequence, Action action, Event event)
                throws Exception {
            byte[] bytes = STATES.get(id(key, sequence, action, event));
            return bytes == null ? null : ActionStateSerde.deserialize(bytes);
        }

        @Override
        public void rebuildState(List<Object> markers) {}

        @Override
        public void pruneState(Object key, long sequence) {}

        @Override
        public void close() {}
    }
}
