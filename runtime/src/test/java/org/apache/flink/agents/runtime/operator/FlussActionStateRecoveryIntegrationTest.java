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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateKeyEncoder;
import org.apache.flink.agents.runtime.actionstate.FlussActionStateStore;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.ConnectionFactory;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.server.testutils.FlussClusterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Fluss writes and operator checkpoints must preserve pending durable results. */
public class FlussActionStateRecoveryIntegrationTest {
    private static final int MAX_PARALLELISM = 128;
    private static final AtomicInteger CALLS = new AtomicInteger();

    @RegisterExtension
    static final FlussClusterExtension FLUSS_CLUSTER =
            FlussClusterExtension.builder().setNumOfTabletServers(1).build();

    @Test
    void pendingCallSurvivesRepeatedCheckpointRestoreAndCompletes() throws Exception {
        CALLS.set(0);
        Action action =
                new Action(
                        "count",
                        new JavaFunction(
                                FlussActionStateRecoveryIntegrationTest.class,
                                "runAction",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(InputEvent.EVENT_TYPE));
        AgentPlan plan = new AgentPlan(Map.of(action.getName(), action));
        AgentConfiguration config = new AgentConfiguration();
        config.set(FLUSS_BOOTSTRAP_SERVERS, FLUSS_CLUSTER.getBootstrapServers());
        config.set(FLUSS_ACTION_STATE_DATABASE, "checkpoint_recovery");
        config.set(FLUSS_ACTION_STATE_TABLE, "pending_results");
        config.set(FLUSS_ACTION_STATE_TABLE_BUCKETS, 2);
        OperatorSubtaskState snapshot;
        Event pendingEvent;
        try (FlussActionStateStore store = store(config);
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                        harness(plan, store)) {
            try (Connection connection =
                            ConnectionFactory.createConnection(FLUSS_CLUSTER.getClientConfig());
                    Admin admin = connection.getAdmin()) {
                FLUSS_CLUSTER.waitUntilTableReady(
                        admin.getTableInfo(TablePath.of("checkpoint_recovery", "pending_results"))
                                .get()
                                .getTableId());
            }
            harness.open();
            harness.processElement(new StreamRecord<>(7L));
            ActionExecutionOperator<Long, Object> operator = operator(harness);
            operator.setCurrentKey(7L);
            ListState<ActionTask> tasks =
                    operator.getRuntimeContext()
                            .getListState(
                                    new ListStateDescriptor<>(
                                            "actionTasks", TypeInformation.of(ActionTask.class)));
            ActionTask queued = tasks.get().iterator().next();
            pendingEvent = queued.getEvent();
            // Deterministically yield after the real durable call on every supported JDK.
            // The operator handles the unfinished result and checkpoints a normal Java task.
            tasks.update(List.of(new CallThenYieldTask(queued)));
            harness.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
            assertThat(CALLS.get()).isEqualTo(1);
            assertThat(harness.getRecordOutput()).isEmpty();
            assertThat(tasks.get()).hasSize(1);
            ActionState pending = store.get(7L, 0L, action, pendingEvent);
            assertThat(pending.isCompleted()).isFalse();
            assertThat(pending.getCallResults()).hasSize(1);
            snapshot = harness.snapshot(1L, 1L);
            harness.notifyOfCompletedCheckpoint(1L);
            assertThat(store.get(7L, 0L, action, pendingEvent)).isNotNull();
        }

        try (FlussActionStateStore store = store(config);
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                        harness(plan, store)) {
            harness.initializeState(snapshot);
            harness.open();
            assertThat(store.get(7L, 0L, action, pendingEvent)).isNotNull();
            // Checkpoint again before replay runs: recovered cache entries need protection too.
            snapshot = harness.snapshot(2L, 2L);
            harness.notifyOfCompletedCheckpoint(2L);
        }

        try (FlussActionStateStore store = store(config);
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                        harness(plan, store)) {
            harness.initializeState(snapshot);
            harness.open();
            operator(harness).waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput()).hasSize(1);
            assertThat(harness.getRecordOutput().iterator().next().getValue()).isEqualTo(21L);
            assertThat(CALLS.get()).as("persisted call must not execute again").isEqualTo(1);
            snapshot = harness.snapshot(3L, 3L);
            harness.notifyOfCompletedCheckpoint(3L);
            assertThat(store.get(7L, 0L, action, pendingEvent)).isNull();
            // Pruning must stop refreshing the completed input's records.
            assertThat(store.getRecoveryMarker()).isEqualTo(store.getRecoveryMarker());
        }

        // Completing and pruning after checkpoint 3 must not invalidate that checkpoint.
        try (FlussActionStateStore store = store(config);
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                        harness(plan, store)) {
            harness.initializeState(snapshot);
            harness.open();
            operator(harness).waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput()).isEmpty();
            assertThat(CALLS.get()).isEqualTo(1);
        }
    }

    public static void runAction(Event event, RunnerContext context) throws Exception {
        context.sendEvent(new OutputEvent(context.durableExecute(new CountingCall())));
    }

    private static class CountingCall implements DurableCallable<Long> {
        public String getId() {
            return "count";
        }

        public Class<Long> getResultClass() {
            return Long.class;
        }

        public Long call() {
            CALLS.incrementAndGet();
            return 21L;
        }
    }

    /**
     * Only the suspension is simulated; persistence, task requeueing and replay use runtime code.
     */
    private static class CallThenYieldTask extends JavaActionTask {
        CallThenYieldTask(ActionTask task) {
            super(
                    task.getKey(),
                    task.getEvent(),
                    task.getAction(),
                    task.getSequenceNumber(),
                    task.getTraceContext());
        }

        @Override
        public ActionTaskResult invoke(ClassLoader loader, PythonActionExecutor executor)
                throws Exception {
            runnerContext.durableExecute(new CountingCall());
            return new ActionTaskResult(
                    false,
                    List.of(),
                    new JavaActionTask(key, event, action, sequenceNumber, traceContext));
        }
    }

    private static FlussActionStateStore store(AgentConfiguration config) {
        return new FlussActionStateStore(
                config, new ActionStateKeyEncoder(MAX_PARALLELISM, LongSerializer.INSTANCE));
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            AgentPlan plan, FlussActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true, store),
                (KeySelector<Long, Long>) value -> value,
                TypeInformation.of(Long.class),
                MAX_PARALLELISM,
                1,
                0);
    }

    @SuppressWarnings("unchecked")
    private static ActionExecutionOperator<Long, Object> operator(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness) {
        return (ActionExecutionOperator<Long, Object>) harness.getOperator();
    }
}
