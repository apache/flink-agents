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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.SinkFunction;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Argument-contract tests for {@link KafkaActionStateCleanupTool}. */
class KafkaActionStateCleanupToolTest {

    @Test
    void testRequiresCommand() {
        assertThatThrownBy(() -> KafkaActionStateCleanupTool.main(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage:");
    }

    @Test
    void testHelpDoesNotRequireKafkaOrFlink() {
        assertDoesNotThrow(() -> KafkaActionStateCleanupTool.main(new String[] {"--help"}));
    }

    @Test
    void testRejectsUnknownApplyOptionBeforeReadingPlan() {
        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupTool.main(
                                        new String[] {
                                            "apply",
                                            "--plan",
                                            "missing.json",
                                            "--bootstrap-servers",
                                            "localhost:9092",
                                            "--control-topic",
                                            "control",
                                            "--typo",
                                            "value"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown option: --typo");
    }

    @Test
    void testPlanRequiresExactlyOneOperatorIdentifier() {
        assertThatThrownBy(
                        () ->
                                KafkaActionStateCleanupTool.main(
                                        new String[] {
                                            "plan",
                                            "--checkpoint",
                                            "checkpoint",
                                            "--output",
                                            "plan.json",
                                            "--operator-uid",
                                            "uid",
                                            "--operator-uid-hash",
                                            "hash"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one");
    }

    @Test
    void testPlanReadsRealUnionStateFromSavepoint(@TempDir Path tempDirectory) throws Exception {
        String operatorUid = "cleanup-plan-test";
        Path readyPath = tempDirectory.resolve("ready");
        Path savepointDirectory = tempDirectory.resolve("savepoints");
        Path planPath = tempDirectory.resolve("cleanup-plan.json");
        Files.createDirectories(savepointDirectory);
        StreamExecutionEnvironment environment =
                StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(1);
        environment.enableCheckpointing(1000);
        environment
                .addSource(new WaitingSource())
                .map(
                        new RecoveryMarkerStatefulMapFunction(
                                readyPath.toString(),
                                List.<Object>of(
                                        marker(Map.of(0, 15L, 1, 20L)),
                                        marker(Map.of(0, 10L, 1, 25L)))))
                .uid(operatorUid)
                .setParallelism(1)
                .addSink(new DiscardingSink<>())
                .setParallelism(1);

        JobClient jobClient = environment.executeAsync("write Kafka cleanup test savepoint");
        String savepoint;
        try {
            waitUntilReady(readyPath);
            savepoint =
                    jobClient
                            .triggerSavepoint(
                                    savepointDirectory.toString(), SavepointFormatType.CANONICAL)
                            .get(30, TimeUnit.SECONDS);
        } finally {
            jobClient.cancel().get(30, TimeUnit.SECONDS);
        }

        KafkaActionStateCleanupTool.main(
                new String[] {
                    "plan",
                    "--checkpoint",
                    savepoint,
                    "--operator-uid",
                    operatorUid,
                    "--output",
                    planPath.toString()
                });

        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromJson(
                        Files.readString(planPath, StandardCharsets.UTF_8));
        assertThat(plan.getOffsets()).isEqualTo(Map.of(0, 10L, 1, 20L));
        assertThat(plan.getSourceRecoveryPoint()).isEqualTo(savepoint);
    }

    private static KafkaActionStateRecoveryMarker marker(Map<Integer, Long> offsets) {
        return new KafkaActionStateRecoveryMarker("action-state", "topic-id", offsets);
    }

    private static void waitUntilReady(Path readyPath) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(readyPath) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(readyPath).exists();
    }

    private static final class WaitingSource extends RichParallelSourceFunction<Integer> {

        private volatile boolean running = true;

        @Override
        public void run(SourceContext<Integer> context) throws Exception {
            synchronized (context.getCheckpointLock()) {
                context.collect(1);
            }
            while (running) {
                Thread.sleep(20);
            }
        }

        @Override
        public void cancel() {
            running = false;
        }
    }

    private static final class RecoveryMarkerStatefulMapFunction
            extends RichMapFunction<Integer, Integer> implements CheckpointedFunction {

        private transient ListState<Object> markerState;
        private final String readyPath;
        private final List<Object> markers;

        private RecoveryMarkerStatefulMapFunction(String readyPath, List<Object> markers) {
            this.readyPath = readyPath;
            this.markers = List.copyOf(markers);
        }

        @Override
        public Integer map(Integer value) throws Exception {
            Files.writeString(Path.of(readyPath), "ready", StandardCharsets.UTF_8);
            return value;
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            markerState.update(markers);
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            markerState =
                    context.getOperatorStateStore()
                            .getUnionListState(
                                    new ListStateDescriptor<>(
                                            KafkaActionStateRecoveryMarker.UNION_STATE_NAME,
                                            TypeInformation.of(Object.class)));
        }
    }

    private static final class DiscardingSink<T> implements SinkFunction<T> {}
}
