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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.migration.FlinkAgentsVersion;
import org.apache.flink.agents.runtime.migration.SnapshotsGenerator;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.OperatorSnapshotUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restores {@link ActionExecutionOperator} snapshots written by earlier Flink Agents releases.
 *
 * <p>The snapshots live in {@code src/test/resources} and are written by the {@link
 * SnapshotsGenerator} methods on the release branch of each version. The test agent's actions and
 * the generated input must stay the same across releases, so a restore failure points at a state or
 * serializer change rather than a change in this test.
 */
class ActionExecutionOperatorMigrationTest {

    static Collection<FlinkAgentsVersion> versions() {
        return FlinkAgentsVersion.publishedVersions();
    }

    /**
     * Snapshot taken after every input finished, so no action or input is pending. Inputs are keyed
     * by parity: key 1 has seen 1 and 3, key 0 has seen 2.
     */
    @SnapshotsGenerator
    void writeAtRestSnapshot(FlinkAgentsVersion version) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness = harness()) {
            harness.open();
            processAndWait(harness, 1L);
            processAndWait(harness, 3L);
            processAndWait(harness, 2L);
            assertThat(outputValues(harness)).containsExactly(1L, 4L, 2L);

            OperatorSnapshotUtil.writeStateHandle(
                    harness.snapshot(0L, 0L), "src/test/resources/" + atRestSnapshot(version));
        }
    }

    @ParameterizedTest(name = "Migration snapshot: {0}")
    @MethodSource("versions")
    void restoresStateAtRest(FlinkAgentsVersion version) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness = harness()) {
            harness.initializeState(
                    OperatorSnapshotUtil.getResourceFilename(atRestSnapshot(version)));
            harness.open();

            AbstractStreamOperator<?> operator = (AbstractStreamOperator<?>) harness.getOperator();
            assertThat(sequenceNumber(operator, 1L)).isEqualTo(1L);
            assertThat(sequenceNumber(operator, 0L)).isEqualTo(0L);
            for (long key : new long[] {0L, 1L}) {
                assertThat(pending(operator, key, OperatorStateManager.ACTION_TASK_STATE_NAME))
                        .isEmpty();
                assertThat(
                                pending(
                                        operator,
                                        key,
                                        OperatorStateManager.PENDING_INPUT_EVENT_STATE_NAME))
                        .isEmpty();
            }
            assertThat(operatorState(operator, "currentProcessingKeys", Object.class)).isEmpty();

            // The operator keeps the job identifier from the snapshot instead of taking this
            // harness's job ID. The Java runner context doesn't expose it, so read the field.
            List<String> storedJobIdentifier =
                    operatorState(operator, "identifier_state", String.class);
            assertThat(storedJobIdentifier).hasSize(1);
            assertThat(jobIdentifier(operator)).isEqualTo(storedJobIdentifier.get(0));
            assertThat(JobID.fromHexString(storedJobIdentifier.get(0)))
                    .isNotEqualTo(harness.getEnvironment().getJobID());

            // The short-term memory keeps each key's running total, so processing continues from
            // it.
            processAndWait(harness, 1L);
            processAndWait(harness, 2L);
            processAndWait(harness, 5L);
            assertThat(outputValues(harness)).containsExactly(5L, 4L, 10L);
            assertThat(sequenceNumber(operator, 1L)).isEqualTo(3L);
            assertThat(sequenceNumber(operator, 0L)).isEqualTo(1L);
        }
    }

    private static String atRestSnapshot(FlinkAgentsVersion version) {
        return "action-execution-operator-migration-at-rest-flink-agents" + version + "-snapshot";
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness()
            throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory(MigrationTestAgent.agentPlan(), true),
                (KeySelector<Long, Long>) value -> value % 2,
                TypeInformation.of(Long.class));
    }

    private static void processAndWait(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness, long input)
            throws Exception {
        harness.processElement(new StreamRecord<>(input));
        ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                .waitInFlightEventsFinished();
    }

    private static List<Object> outputValues(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness) {
        List<Object> values = new ArrayList<>();
        for (Object record : harness.extractOutputStreamRecords()) {
            values.add(((StreamRecord<?>) record).getValue());
        }
        harness.getOutput().clear();
        return values;
    }

    private static Long sequenceNumber(AbstractStreamOperator<?> operator, long key)
            throws Exception {
        ValueState<Long> state =
                keyedState(
                        operator,
                        key,
                        new ValueStateDescriptor<>(
                                OperatorStateManager.MESSAGE_SEQUENCE_NUMBER_STATE_NAME,
                                Long.class));
        return state.value();
    }

    private static List<Object> pending(
            AbstractStreamOperator<?> operator, long key, String stateName) throws Exception {
        ListState<Object> state =
                keyedState(
                        operator,
                        key,
                        new ListStateDescriptor<>(stateName, TypeInformation.of(Object.class)));
        List<Object> values = new ArrayList<>();
        Iterable<Object> stored = state.get();
        if (stored != null) {
            stored.forEach(values::add);
        }
        return values;
    }

    private static <S extends State> S keyedState(
            AbstractStreamOperator<?> operator, long key, StateDescriptor<S, ?> descriptor)
            throws Exception {
        KeyedStateBackend<Object> backend = operator.getKeyedStateBackend();
        backend.setCurrentKey(key);
        return backend.getPartitionedState(
                VoidNamespace.INSTANCE, VoidNamespaceSerializer.INSTANCE, descriptor);
    }

    private static String jobIdentifier(AbstractStreamOperator<?> operator) throws Exception {
        Field field = ActionExecutionOperator.class.getDeclaredField("jobIdentifier");
        field.setAccessible(true);
        return (String) field.get(operator);
    }

    private static <T> List<T> operatorState(
            AbstractStreamOperator<?> operator, String stateName, Class<T> type) throws Exception {
        List<T> values = new ArrayList<>();
        operator.getOperatorStateBackend()
                .getUnionListState(new ListStateDescriptor<>(stateName, type))
                .get()
                .forEach(values::add);
        return values;
    }

    /** Agent with one action that keeps a running total per key in short-term memory. */
    public static class MigrationTestAgent {

        private static final String TOTAL = "total";

        static AgentPlan agentPlan() throws Exception {
            Action accumulate =
                    new Action(
                            "accumulate",
                            new JavaFunction(
                                    MigrationTestAgent.class,
                                    "accumulate",
                                    new Class<?>[] {Event.class, RunnerContext.class}),
                            List.of(InputEvent.EVENT_TYPE));
            return new AgentPlan(Map.of(accumulate.getName(), accumulate));
        }

        public static void accumulate(Event event, RunnerContext context) throws Exception {
            long input = (Long) InputEvent.fromEvent(event).getInput();
            MemoryObject memory = context.getShortTermMemory();
            long total = memory.isExist(TOTAL) ? (Long) memory.get(TOTAL).getValue() : 0L;
            memory.set(TOTAL, total + input);
            context.sendEvent(new OutputEvent(total + input));
        }
    }
}
