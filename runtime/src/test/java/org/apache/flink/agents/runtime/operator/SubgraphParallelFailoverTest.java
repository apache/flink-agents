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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.SinkFunction;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real Flink job that fails mid-stream and recovers from its own checkpoint, for both kinds of
 * internal graph call on the JDK&lt;21 parallel execution engine.
 *
 * <p>Recovery is the part a harness cannot cover: the operator drains in-flight work before it
 * snapshots, so a harness can only snapshot a settled record. Here the task genuinely fails after a
 * checkpoint completed, Flink restores the agent operator, and the remaining records must still run
 * their graph calls to completion. A regression that only deadlocks on a restored operator — a
 * coordinator that never re-arms, a call whose bootstrap is not re-dispatched — shows up as a job
 * that never finishes, which the timeout turns into a failure.
 */
@EnabledForJreRange(max = JRE.JAVA_20)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(300)
public class SubgraphParallelFailoverTest {

    private static final String[] INPUTS = {"1", "2", "3"};
    private static final long CHECKPOINT_INTERVAL_MS = 200;

    @RegisterExtension
    static final MiniClusterExtension MINI_CLUSTER =
            new MiniClusterExtension(
                    new org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
                                    .Builder()
                            .setNumberTaskManagers(1)
                            .setNumberSlotsPerTaskManager(3)
                            .build());

    /** Completed checkpoints, shared across attempts because the MiniCluster is one JVM. */
    private static final AtomicInteger CHECKPOINTS_COMPLETED = new AtomicInteger();

    /** Gates the single injected failure, so only the first post-checkpoint record throws. */
    private static final AtomicInteger FAILURES_INJECTED = new AtomicInteger();

    /** How many times the sink actually threw, so a green run cannot mean "nothing failed". */
    private static final AtomicInteger INJECTIONS = new AtomicInteger();

    private static final ConcurrentLinkedQueue<String> OUTPUTS = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void reset() {
        CHECKPOINTS_COMPLETED.set(0);
        FAILURES_INJECTED.set(0);
        INJECTIONS.set(0);
        OUTPUTS.clear();
    }

    /** Which internal graph call the job's root action makes. */
    enum Kind {
        CHAT {
            @Override
            AgentPlan plan() throws Exception {
                return SubgraphParallelExecutionTest.chatPlan();
            }

            @Override
            String expected(String input) {
                return "answer:" + input;
            }
        },
        SUBAGENT {
            @Override
            AgentPlan plan() throws Exception {
                return SubgraphParallelExecutionTest.subagentPlan();
            }

            @Override
            String expected(String input) {
                return input;
            }
        },
        NESTED_SUBAGENT {
            @Override
            AgentPlan plan() throws Exception {
                return SubgraphParallelExecutionTest.nestedSubagentPlan();
            }

            @Override
            String expected(String input) {
                return "child(" + input + ")";
            }
        };

        abstract AgentPlan plan() throws Exception;

        abstract String expected(String input);
    }

    /**
     * Emits one record per checkpoint window, so a checkpoint always completes between records and
     * the failure below is guaranteed to restore from real checkpointed state.
     */
    public static class GatedSource extends RichParallelSourceFunction<String> {
        private static final long serialVersionUID = 1L;
        private volatile boolean running = true;

        @Override
        public void run(SourceContext<String> context) throws Exception {
            // Re-based per attempt: after a restart the counter is already ahead, and waiting on an
            // absolute count would let every replayed record flood in at once, multiplying the
            // workers the graph calls need beyond what nesting alone requires.
            int base = CHECKPOINTS_COMPLETED.get();
            for (int i = 0; i < INPUTS.length && running; i++) {
                synchronized (context.getCheckpointLock()) {
                    context.collect(INPUTS[i]);
                }
                if (i + 1 < INPUTS.length) {
                    awaitCheckpoints(base + i + 1);
                }
            }
        }

        private void awaitCheckpoints(int required) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (running
                    && CHECKPOINTS_COMPLETED.get() < required
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(CHECKPOINTS_COMPLETED.get())
                    .as("checkpoint %d never completed", required)
                    .isGreaterThanOrEqualTo(required);
        }

        @Override
        public void cancel() {
            running = false;
        }
    }

    /** Records every output and fails the task once, after a checkpoint has completed. */
    public static class FailingSink implements SinkFunction<String>, CheckpointListener {
        private static final long serialVersionUID = 1L;

        @Override
        public void invoke(String value, Context context) {
            OUTPUTS.add(value);
            if (CHECKPOINTS_COMPLETED.get() >= 1 && FAILURES_INJECTED.getAndIncrement() == 0) {
                INJECTIONS.incrementAndGet();
                throw new IllegalStateException("injected sink failure");
            }
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            CHECKPOINTS_COMPLETED.incrementAndGet();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void recoversGraphCallAfterTaskFailure(Kind kind) throws Exception {
        Configuration configuration = new Configuration();
        configuration.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
        configuration.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 1);
        configuration.set(
                RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofMillis(100));
        configuration.set(StateBackendOptions.STATE_BACKEND, "hashmap");
        configuration.set(CheckpointingOptions.CHECKPOINT_STORAGE, "jobmanager");

        AgentPlan plan = kind.plan();
        // On this engine a caller waiting for its graph keeps its worker and only hands off the
        // lock, so the pool must cover the deepest chain of simultaneously waiting callers — here
        // three levels. Deliberately tight: a wait that started pinning extra workers would starve
        // and time the checkpoint out rather than pass quietly.
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 4);

        try (StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(configuration)) {
            env.setParallelism(1);
            env.disableOperatorChaining();
            env.enableCheckpointing(CHECKPOINT_INTERVAL_MS);
            env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
            env.getCheckpointConfig().setCheckpointTimeout(60_000);

            env.addSource(new GatedSource())
                    .name("checkpoint-gated-source")
                    .uid("source")
                    .keyBy(value -> value)
                    .transform(
                            "parallel-subgraph",
                            Types.STRING,
                            new ActionExecutionOperatorFactory<String, String>(plan, true))
                    .uid("agent")
                    .addSink(new FailingSink())
                    .name("failing-sink")
                    .uid("sink");

            JobClient job = env.executeAsync("subgraph-parallel-failover-" + kind);
            Throwable jobFailure = null;
            JobStatus status;
            try {
                job.getJobExecutionResult().get(180, TimeUnit.SECONDS);
            } catch (Throwable t) {
                jobFailure = t;
            } finally {
                status = job.getJobStatus().get(10, TimeUnit.SECONDS);
                if (status == JobStatus.RUNNING
                        || status == JobStatus.CREATED
                        || status == JobStatus.RESTARTING) {
                    // Only a still-live job needs stopping; cancelling a terminated one would
                    // mask the failure being reported below.
                    job.cancel().get(10, TimeUnit.SECONDS);
                }
            }
            // Report the job's own failure first: it carries the cause, the status does not.
            if (jobFailure != null) {
                throw new AssertionError("Job did not finish, reached " + status, jobFailure);
            }
            assertThat(status).isEqualTo(JobStatus.FINISHED);
        }

        // The failure fired exactly once, a checkpoint preceded it, and the job still completed.
        assertThat(INJECTIONS.get()).isEqualTo(1);
        assertThat(CHECKPOINTS_COMPLETED.get()).isPositive();

        Set<String> expected = new LinkedHashSet<>();
        for (String input : INPUTS) {
            expected.add(kind.expected(input));
        }
        // Recovery is at-least-once, so a replayed record may be recorded twice; what must hold is
        // that every record's graph call completed and no partial or corrupted result leaked out.
        List<String> recorded = new ArrayList<>(OUTPUTS);
        assertThat(new LinkedHashSet<>(recorded)).isEqualTo(expected);
        assertThat(recorded).allSatisfy(value -> assertThat(expected).contains(value));
    }
}
