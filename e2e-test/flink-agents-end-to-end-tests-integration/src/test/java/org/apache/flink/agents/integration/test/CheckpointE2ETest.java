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
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.OperatorSnapshotFutures;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.test.junit5.InjectMiniCluster;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.apache.flink.runtime.testutils.CommonTestUtils.waitForAllTaskRunning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Checkpoint progress, abort, and recovery through a real MiniCluster. File gates control external
 * calls; downstream output markers prove that results left the Agent operator. No external
 * ActionStateStore is configured, so recovery depends on Flink state alone.
 *
 * <p>Worker saturation, commit ordering, backends, and nested graphs have focused runtime tests.
 * This suite checks the coordinator/barrier path on both worker and continuation engines.
 */
@Timeout(120)
class CheckpointE2ETest {
    @RegisterExtension
    static final MiniClusterExtension CLUSTER =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setNumberTaskManagers(1)
                            .setNumberSlotsPerTaskManager(4)
                            .build());

    @TempDir Path directory;
    private MiniCluster miniCluster;

    @BeforeEach
    void setCluster(@InjectMiniCluster MiniCluster cluster) {
        miniCluster = cluster;
    }

    @Test
    void checkpointsProgressWhileIdleAndAcrossBusyKeys() throws Exception {
        try (JobRun run = new JobRun(null)) {
            run.checkpoint(); // idle baseline
            Path a = run.send("a", "held", true);
            Path b = run.send("b", "held", true);
            run.await(
                    () -> Files.exists(a.resolve("entered")) && Files.exists(b.resolve("entered")));
            Path queued = run.send("a", "queued", false);
            // An unrelated key keeps making progress while two calls remain blocked.
            for (int i = 1; i <= 2; i++) {
                run.expectOutput(run.send("c", "fast-" + i, false), i);
                run.checkpoint();
            }
            // Deterministic gates, rather than sleeps: neither call can have finished early.
            assertThat(Files.exists(a.resolve("output"))).isFalse();
            assertThat(Files.exists(b.resolve("output"))).isFalse();
            assertThat(Files.exists(queued.resolve("entered"))).isFalse();
            release(a);
            release(b);
            run.expectOutput(a, 1);
            run.expectOutput(queued, 2);
            run.expectOutput(b, 1);
            run.checkpoint();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkpointFailureDoesNotStopActionsOrLaterCheckpoints(boolean timeout) throws Exception {
        try (JobRun run = new JobRun(null)) {
            run.checkpoint();
            Path held = run.send("a", "held", true);
            run.await(() -> Files.exists(held.resolve("entered")));
            Files.writeString(directory.resolve("fail-next-checkpoint"), String.valueOf(timeout));
            Throwable failure = catchThrowable(run::checkpoint);
            CheckpointException checkpointFailure =
                    ExceptionUtils.findThrowable(failure, CheckpointException.class)
                            .orElseThrow(() -> new AssertionError(failure));
            assertThat(checkpointFailure.getCheckpointFailureReason())
                    .isEqualTo(
                            timeout
                                    ? CheckpointFailureReason.CHECKPOINT_EXPIRED
                                    : CheckpointFailureReason.CHECKPOINT_ASYNC_EXCEPTION);
            Files.createFile(directory.resolve("release-checkpoint"));
            assertThat(run.job.getJobStatus().get(5, TimeUnit.SECONDS))
                    .isEqualTo(JobStatus.RUNNING);
            run.checkpoint(); // succeeds while the same Action is still blocked
            assertThat(Files.exists(held.resolve("output"))).isFalse();
            release(held);
            run.expectOutput(held, 1);
            run.expectOutput(run.send("a", "next", false), 2);
            run.checkpoint();
        }
    }

    @Test
    void restoresCommittedMemoryAndPendingInputs() throws Exception {
        String checkpoint;
        Path pending;
        Path queued;
        try (JobRun run = new JobRun(null)) {
            run.expectOutput(run.send("a", "committed", false), 1);
            pending = run.send("a", "pending", true);
            run.await(() -> Files.exists(pending.resolve("entered")));
            queued = run.send("a", "queued", false);
            run.expectOutput(run.send("b", "committed", false), 1);
            checkpoint = run.checkpoint();
            assertThat(Files.exists(queued.resolve("entered"))).isFalse();
        }
        Files.delete(pending.resolve("entered"));
        // All inputs crossed the source before the checkpoint. Removing their files proves
        // that recovery uses Agent state, not fresh records from the source.
        try (var files = Files.list(directory.resolve("input"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.delete(file);
            }
        }
        try (JobRun run = new JobRun(checkpoint)) {
            run.await(() -> Files.exists(pending.resolve("entered")));
            run.checkpoint();
            release(pending);
            run.expectOutput(pending, 2);
            run.expectOutput(queued, 3);
            run.expectOutput(run.send("a", "next", false), 4);
            run.expectOutput(run.send("b", "next", false), 2);
            // The output writer rejects duplicates, including replay of either committed input.
            run.checkpoint();
        }
    }

    /** One graph and one set of file handshakes for all checkpoint scenarios. */
    private final class JobRun implements AutoCloseable {
        private final StreamExecutionEnvironment env;
        private final JobClient job;
        private final CompletableFuture<JobExecutionResult> result;

        private JobRun(String restorePath) throws Exception {
            Files.createDirectories(directory.resolve("input"));
            Configuration config = new Configuration();
            config.setString("restart-strategy.type", "none");
            config.setString("state.backend.type", "hashmap");
            config.setString(
                    "execution.checkpointing.dir", directory.resolve("cp").toUri().toString());
            config.setString(
                    "execution.checkpointing.externalized-checkpoint-retention",
                    "RETAIN_ON_CANCELLATION");
            if (restorePath != null) {
                config.setString("execution.savepoint.path", restorePath);
            }
            env = StreamExecutionEnvironment.getExecutionEnvironment(config);
            env.setParallelism(1);
            env.enableCheckpointing(Long.MAX_VALUE); // explicit triggers after each handshake
            env.getCheckpointConfig().setCheckpointTimeout(5_000);
            env.getCheckpointConfig().setTolerableCheckpointFailureNumber(10);
            FileSource<String> source =
                    FileSource.forRecordStreamFormat(
                                    new TextLineInputFormat(),
                                    new org.apache.flink.core.fs.Path(
                                            directory.resolve("input").toUri()))
                            .monitorContinuously(Duration.ofMillis(100))
                            .build();
            AgentsExecutionEnvironment agents =
                    AgentsExecutionEnvironment.getExecutionEnvironment(env);
            agents.getConfig().set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, true);
            agents.fromDataStream(
                            env.fromSource(source, WatermarkStrategy.noWatermarks(), "trigger")
                                    .uid("trigger")
                                    .map(
                                            value -> {
                                                Files.createFile(Path.of(value, "submitted"));
                                                return value;
                                            }),
                            (KeySelector<String, String>)
                                    value -> Path.of(value).getParent().getFileName().toString())
                    .apply(new CountingAgent())
                    .toDataStream()
                    .transform(
                            "checkpoint-storage",
                            TypeInformation.of(Object.class),
                            new FailingSnapshotOperator(
                                    directory.resolve("fail-next-checkpoint").toString()))
                    .map(
                            value -> {
                                String[] parts = value.toString().split("\\|");
                                Path temporary = Path.of(parts[0], "output.tmp");
                                Files.writeString(
                                        temporary, parts[1], StandardOpenOption.CREATE_NEW);
                                Files.move(temporary, Path.of(parts[0], "output"));
                                return value;
                            })
                    .print();
            job = env.executeAsync("checkpoint-e2e");
            result = job.getJobExecutionResult();
            waitForAllTaskRunning(miniCluster, job.getJobID(), false);
        }

        private Path send(String key, String id, boolean blocked) throws Exception {
            Path record = directory.resolve("records").resolve(key).resolve(id);
            Files.createDirectories(record);
            if (!blocked) {
                release(record);
            }
            Files.writeString(directory.resolve("input").resolve(key + "-" + id), record + "\n");
            await(() -> Files.exists(record.resolve("submitted")));
            return record;
        }

        private String checkpoint() throws Exception {
            String path = miniCluster.triggerCheckpoint(job.getJobID()).get(30, TimeUnit.SECONDS);
            assertThat(path).isNotBlank();
            return path;
        }

        private void expectOutput(Path record, int value) throws Exception {
            await(() -> Files.exists(record.resolve("output")));
            assertThat(Files.readString(record.resolve("output"))).isEqualTo(String.valueOf(value));
        }

        private void await(BooleanSupplier condition) throws Exception {
            CheckpointE2ETest.await(() -> result.isDone() || condition.getAsBoolean());
            if (result.isDone()) {
                result.get(); // expose the job's actual failure instead of a marker timeout
                throw new AssertionError("Unbounded job terminated unexpectedly");
            }
        }

        @Override
        public void close() throws Exception {
            try {
                Files.writeString(directory.resolve("release-checkpoint"), "");
                if (!result.isDone()) {
                    job.cancel().get(30, TimeUnit.SECONDS);
                    CheckpointE2ETest.await(result::isDone);
                }
            } finally {
                env.close();
            }
        }
    }

    private static void release(Path record) throws IOException {
        Files.writeString(record.resolve("release"), "");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("condition before deadline").isTrue();
    }

    /** A per-key counter, with a controllable external call before committing the next value. */
    public static class CountingAgent extends Agent {
        @Action(EventType.InputEvent)
        public static void run(Event event, RunnerContext ctx) throws Exception {
            String path = (String) InputEvent.fromEvent(event).getInput();
            int previous =
                    ctx.getShortTermMemory().isExist("count")
                            ? ((Number) ctx.getShortTermMemory().get("count").getValue()).intValue()
                            : 0;
            Integer increment =
                    ctx.durableExecuteAsync(
                                    new DurableCallable<Integer>() {
                                        @Override
                                        public Integer call() throws Exception {
                                            Files.writeString(Path.of(path, "entered"), "");
                                            await(() -> Files.exists(Path.of(path, "release")));
                                            return 1;
                                        }

                                        @Override
                                        public String getId() {
                                            return "gated-call";
                                        }

                                        @Override
                                        public Class<Integer> getResultClass() {
                                            return Integer.class;
                                        }
                                    })
                            .await();
            ctx.getShortTermMemory().set("count", previous + increment);
            ctx.sendEvent(new OutputEvent(path + "|" + (previous + increment)));
        }
    }

    /** Fails or stalls one async snapshot write to exercise coordinator-driven aborts. */
    private static class FailingSnapshotOperator extends AbstractStreamOperator<Object>
            implements OneInputStreamOperator<Object, Object> {
        private final String failMarker;

        private FailingSnapshotOperator(String failMarker) {
            this.failMarker = failMarker;
        }

        @Override
        public void processElement(StreamRecord<Object> element) {
            output.collect(element);
        }

        @Override
        public OperatorSnapshotFutures snapshotState(
                long checkpointId,
                long timestamp,
                CheckpointOptions options,
                CheckpointStreamFactory storage)
                throws Exception {
            OperatorSnapshotFutures futures =
                    super.snapshotState(checkpointId, timestamp, options, storage);
            Path marker = Path.of(failMarker);
            if (Files.exists(marker)) {
                boolean timeout = Boolean.parseBoolean(Files.readString(marker));
                Files.delete(marker);
                futures.setOperatorStateManagedFuture(
                        new FutureTask<>(
                                () -> {
                                    if (timeout) {
                                        // Simulate unavailable storage until the test observes
                                        // the timeout and explicitly clears the fault.
                                        await(
                                                () ->
                                                        Files.exists(
                                                                marker.resolveSibling(
                                                                        "release-checkpoint")));
                                    }
                                    throw new IOException("Injected checkpoint storage failure");
                                }));
            }
            return futures;
        }
    }
}
