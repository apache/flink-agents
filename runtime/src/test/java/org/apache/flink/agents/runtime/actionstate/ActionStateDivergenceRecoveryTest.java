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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.writer.AppendWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Operator checkpoint/restore regressions using production stores and serialized backend logs. */
public class ActionStateDivergenceRecoveryTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger READS = new AtomicInteger();

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void normalFanoutReusesAllCompletedActions(String backend) throws Exception {
        assertNormalRecovery(
                backend,
                Map.of(
                        "first", action("first", "count", InputEvent.EVENT_TYPE),
                        "second", action("second", "count", InputEvent.EVENT_TYPE)),
                4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void normalCascadeReusesAllCompletedActions(String backend) throws Exception {
        assertNormalRecovery(
                backend,
                Map.of(
                        "input", action("input", "emitOne", InputEvent.EVENT_TYPE),
                        "child", action("child", "count", "step")),
                4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void normalRepeatedActionWithDifferentEventsReusesAllCompletions(String backend)
            throws Exception {
        assertNormalRecovery(
                backend,
                Map.of(
                        "input", action("input", "emitTwo", InputEvent.EVENT_TYPE),
                        "child", action("child", "count", "step")),
                6);
    }

    /**
     * FLINK-AGENTS-1084 regression guard: two sibling output events with identical attributes (not
     * merely two different ones, as above) must each trigger a genuinely separate action execution
     * of "child" rather than the second silently reusing the first's durable state. Without the
     * occurrence-identity fix this collapses to 4 total calls (1 "input" + 1 "child" per input)
     * instead of 6, because the second "child" execution's state lookup would find the first's
     * just-persisted completed state under the same (then-colliding) key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void normalFanoutWithIdenticalSiblingAttributesStillExecutesBothSiblings(String backend)
            throws Exception {
        assertNormalRecovery(
                backend,
                Map.of(
                        "input", action("input", "emitTwoIdentical", InputEvent.EVENT_TYPE),
                        "child", action("child", "count", "step")),
                6);
    }

    private void assertNormalRecovery(String backend, Map<String, Action> actions, int expected)
            throws Exception {
        CALLS.set(0);
        Journal journal = new Journal(backend);
        AgentPlan plan = plan(backend, actions);
        OperatorSubtaskState checkpoint;
        try (var h = harness(plan, journal)) {
            h.open();
            checkpoint = h.snapshot(1L, 1L);
            input(h, "one");
            input(h, "two");
            assertThat(CALLS.get()).isEqualTo(expected);
        }
        // Cold reconstruction from serialized bytes: never reuse the prior attempt's cache.
        try (var h = restored(plan, journal, checkpoint)) {
            input(h, "one");
            input(h, "two");
            assertThat(CALLS.get())
                    .as("all durably completed Action bodies must be skipped")
                    .isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void changedInputHistoryMustInvalidateLaterResultAcrossTwoRestores(String backend)
            throws Exception {
        CALLS.set(0);
        READS.set(0);
        Journal journal = new Journal(backend);
        AgentPlan plan =
                plan(backend, Map.of("input", action("input", "setOrRead", InputEvent.EVENT_TYPE)));
        OperatorSubtaskState checkpoint;
        try (var h = harness(plan, journal)) {
            h.open();
            checkpoint = h.snapshot(1L, 1L);
            input(h, "A");
        }
        try (var h = restored(plan, journal, checkpoint)) {
            input(h, "B");
            input(h, "read");
            assertThat(h.extractOutputValues()).containsExactly("B");
        }
        assertThat(READS.get()).isEqualTo(1);
        try (var h = restored(plan, journal, checkpoint)) {
            input(h, "A");
            input(h, "read");
            assertThat(h.extractOutputValues())
                    .as("the cached later result belongs to B, not the restored A history")
                    .containsExactly("A");
            assertThat(READS.get()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void changedRootMustNotReuseSameSequenceChildFromOtherHistory(String backend) throws Exception {
        READS.set(0);
        Journal journal = new Journal(backend);
        AgentPlan plan =
                plan(
                        backend,
                        Map.of(
                                "input", action("input", "setAndEmit", InputEvent.EVENT_TYPE),
                                "child", action("child", "readBranch", "step")));
        OperatorSubtaskState checkpoint;
        try (var h = harness(plan, journal)) {
            h.open();
            checkpoint = h.snapshot(1L, 1L);
            input(h, "A");
            assertThat(h.extractOutputValues()).containsExactly("A");
        }
        try (var h = restored(plan, journal, checkpoint)) {
            input(h, "B");
            assertThat(h.extractOutputValues())
                    .as("same child payload does not imply same Memory dependency")
                    .containsExactly("B");
        }
        try (var h = restored(plan, journal, checkpoint)) {
            input(h, "A");
            assertThat(h.extractOutputValues()).containsExactly("A");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss", "kafka-parallel", "fluss-parallel"})
    void checkpointAfterDivergenceMustNotReviveOldSuffix(String backend) throws Exception {
        READS.set(0);
        Journal journal = new Journal(backend);
        AgentPlan plan =
                plan(backend, Map.of("input", action("input", "setOrRead", InputEvent.EVENT_TYPE)));
        OperatorSubtaskState before, after;
        long retainedStart;
        try (var h = harness(plan, journal)) {
            h.open();
            before = h.snapshot(1L, 1L);
            input(h, "A");
            retainedStart = journal.log.size();
            // Another key can keep a shared partition's recovery offset between this key's
            // original input and its old suffix. Its pending record is not checkpoint-covered.
            Event pendingInput = new InputEvent("other-key-pending");
            journal.save(
                    99L,
                    1L,
                    action("input", "setOrRead", InputEvent.EVENT_TYPE),
                    pendingInput,
                    new ActionState(pendingInput));
            input(h, "read");
            assertThat(h.extractOutputValues()).containsExactly("A");
        }
        try (var h = restored(plan, journal, before)) {
            input(h, "B");
            journal.nextMarker = retainedStart;
            after = h.snapshot(2L, 2L);
        }
        try (var h = restored(plan, journal, after)) {
            // Input 1 is already in this checkpoint; there is no get(seq=1) to detect its fork.
            input(h, "read");
            assertThat(h.extractOutputValues())
                    .as("an old durable suffix must stay invalid after a newer checkpoint")
                    .containsExactly("B");
            assertThat(READS.get()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss"})
    void truncatedReplayMustNotMatchOldChildToNewParentsOutput(String backend) throws Exception {
        Journal journal = new Journal(backend);
        Action root = action("input", "setAndEmit", InputEvent.EVENT_TYPE);
        Action child = action("child", "readBranch", "step");
        Event oldInput = new InputEvent("A");
        Event oldChildEvent = new Event("step", Map.of("value", "identical"));
        ActionState oldRoot = new ActionState(oldInput);
        oldRoot.addEvent(oldChildEvent);
        oldRoot.markCompleted();
        journal.save(7L, 1L, root, oldInput, oldRoot);

        long start = journal.log.size();
        // This pending key pins a checkpoint's shared replay boundary after oldRoot.
        Event other = new InputEvent("other-pending");
        journal.save(99L, 1L, root, other, new ActionState(other));
        ActionState oldChild = new ActionState(oldChildEvent);
        oldChild.addEvent(new OutputEvent("A"));
        oldChild.markCompleted();
        journal.save(7L, 1L, child, oldChildEvent, oldChild);

        Event newInput = new InputEvent("B");
        Event newChildEvent = new Event("step", Map.of("value", "identical"));
        ActionState newRoot = new ActionState(newInput);
        newRoot.addEvent(newChildEvent);
        newRoot.markCompleted();
        journal.save(7L, 1L, root, newInput, newRoot);
        // A checkpoint between B's root completion and its child execution restores the new
        // child's pending Event. Backend replay no longer includes the original root A.
        try (ActionStateStore store = journal.newStore()) {
            store.rebuildState(List.of(Map.of(0, start)));
            assertThat(store.get(7L, 1L, child, newChildEvent))
                    .as("the selected parent's output ID differs from the cached child's trigger")
                    .isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "fluss"})
    void regeneratedBootstrapWithoutCompletedProducerMustKeepExistingReplayContract(String backend)
            throws Exception {
        Journal journal = new Journal(backend);
        Action child = action("child", "count", SubgraphEvent.EVENT_TYPE);
        Event oldEvent = new SubgraphEvent("same-invocation", new InputEvent("prompt"), true);
        ActionState completed = new ActionState(oldEvent);
        completed.addEvent(new OutputEvent("saved-child-result"));
        completed.markCompleted();
        try (ActionStateStore store = journal.newStore()) {
            store.put(7L, 1L, child, oldEvent, completed);
        }
        Event regenerated = new SubgraphEvent("same-invocation", new InputEvent("prompt"), true);
        assertThat(regenerated.getId()).isNotEqualTo(oldEvent.getId());
        try (ActionStateStore store = journal.newStore()) {
            store.rebuildState(List.of(Map.of(0, 0L)));
            ActionState reused = store.get(7L, 1L, child, regenerated);
            assertThat(reused).isNotNull();
            assertThat(reused.isCompleted()).isTrue();
            assertThat(reused.getOutputEvents().get(0).getAttr("output"))
                    .isEqualTo("saved-child-result");
        }
    }

    public static void count(Event event, RunnerContext ctx) {
        CALLS.incrementAndGet();
        ctx.sendEvent(new OutputEvent("ok"));
    }

    public static void emitOne(Event event, RunnerContext ctx) {
        CALLS.incrementAndGet();
        ctx.sendEvent(new Event("step", Map.of("value", event.getAttr("input"))));
    }

    public static void emitTwo(Event event, RunnerContext ctx) {
        CALLS.incrementAndGet();
        ctx.sendEvent(new Event("step", Map.of("value", "first")));
        ctx.sendEvent(new Event("step", Map.of("value", "second")));
    }

    /**
     * Emits two sibling output events with IDENTICAL attributes (FLINK-AGENTS-1084's reproduction
     * shape). Before the occurrence-identity fix, both siblings' durable action-state keys
     * collided: the second child's pre-execution lookup found the first child's just-persisted
     * completed state and incorrectly skipped its own real execution, replaying the first child's
     * result instead. See {@link #emitTwo} for the distinct-attributes sibling case this
     * complements.
     */
    public static void emitTwoIdentical(Event event, RunnerContext ctx) {
        CALLS.incrementAndGet();
        ctx.sendEvent(new Event("step", Map.of("value", "same")));
        ctx.sendEvent(new Event("step", Map.of("value", "same")));
    }

    public static void setOrRead(Event event, RunnerContext ctx) throws Exception {
        CALLS.incrementAndGet();
        String value = (String) event.getAttr("input");
        if (value.equals("read")) {
            readBranch(event, ctx);
        } else {
            ctx.getShortTermMemory().set("branch", value);
        }
    }

    public static void setAndEmit(Event event, RunnerContext ctx) throws Exception {
        ctx.getShortTermMemory().set("branch", event.getAttr("input"));
        ctx.sendEvent(new Event("step", Map.of("value", "identical-on-both-paths")));
    }

    public static void readBranch(Event event, RunnerContext ctx) throws Exception {
        READS.incrementAndGet();
        ctx.sendEvent(new OutputEvent(ctx.getShortTermMemory().get("branch").getValue()));
    }

    private static Action action(String name, String method, String eventType) throws Exception {
        return new Action(
                name,
                new JavaFunction(
                        ActionStateDivergenceRecoveryTest.class,
                        method,
                        new Class<?>[] {Event.class, RunnerContext.class}),
                List.of(eventType));
    }

    static AgentPlan plan(String backend, Map<String, Action> actions) {
        AgentPlan plan = new AgentPlan(actions);
        plan.getConfig()
                .set(
                        AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED,
                        backend.endsWith("parallel"));
        return plan;
    }

    static KeyedOneInputStreamOperatorTestHarness<Long, String, String> harness(
            AgentPlan plan, Journal journal) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true, journal.newStore()),
                (KeySelector<String, Long>) value -> 7L,
                TypeInformation.of(Long.class),
                128,
                1,
                0);
    }

    static KeyedOneInputStreamOperatorTestHarness<Long, String, String> restored(
            AgentPlan plan, Journal journal, OperatorSubtaskState checkpoint) throws Exception {
        var h = harness(plan, journal);
        h.initializeState(checkpoint);
        h.open();
        return h;
    }

    static void input(KeyedOneInputStreamOperatorTestHarness<Long, String, String> h, String value)
            throws Exception {
        h.processElement(new StreamRecord<>(value));
        ((ActionExecutionOperator<String, String>) h.getOperator()).waitInFlightEventsFinished();
    }

    /** Models backend last-write-wins recovery; get() eviction never erases these durable bytes. */
    static class Journal {
        final String backend;
        final List<Map.Entry<String, byte[]>> log = new ArrayList<>();
        long nextMarker;
        final ActionStateKeyEncoder encoder =
                new ActionStateKeyEncoder(128, LongSerializer.INSTANCE);

        Journal(String backend) {
            this.backend = backend;
        }

        void save(Object key, long seq, Action action, Event event, ActionState state)
                throws Exception {
            String stateKey = encoder.generateKey(key, seq, action, event);
            byte[] payload = ActionStateSerde.serialize(state);
            log.add(Map.entry(stateKey, payload));
        }

        ActionStateStore newStore() {
            Map<String, ActionState> cache = new ActionStateCache();
            if (backend.startsWith("kafka")) {
                MockConsumer<String, ActionState> consumer = new MockConsumer<>("earliest");
                consumer.updatePartitions(
                        "state", List.of(new PartitionInfo("state", 0, null, null, null)));
                TopicPartition partition = new TopicPartition("state", 0);
                consumer.updateBeginningOffsets(Map.of(partition, 0L));
                consumer.updateEndOffsets(Map.of(partition, (long) log.size()));
                consumer.schedulePollTask(
                        () -> {
                            long start = consumer.position(partition);
                            for (int i = (int) start; i < log.size(); i++) {
                                var entry = log.get(i);
                                consumer.addRecord(
                                        new ConsumerRecord<>(
                                                "state",
                                                0,
                                                i,
                                                entry.getKey(),
                                                ActionStateSerde.deserialize(entry.getValue())));
                            }
                        });
                MockProducer<String, ActionState> producer =
                        new MockProducer<>(
                                true,
                                new ActionStateKeyPartitioner(),
                                new StringSerializer(),
                                new ActionStateKafkaSeder());
                return new KafkaActionStateStore(
                        cache, new AgentConfiguration(), producer, consumer, "state", encoder) {
                    @Override
                    public void put(
                            Object key, long seq, Action action, Event event, ActionState state)
                            throws Exception {
                        super.put(key, seq, action, event, state);
                        save(key, seq, action, event, state);
                    }

                    @Override
                    public Object getRecoveryMarker(Map<?, Long> completed) {
                        return Map.of(0, nextMarker);
                    }
                };
            }
            AppendWriter writer = mock(AppendWriter.class);
            when(writer.append(any())).thenReturn(CompletableFuture.completedFuture(null));
            return new FlussActionStateStore(
                    cache, mock(Connection.class), mock(Table.class), writer, encoder) {
                @Override
                public void put(Object key, long seq, Action action, Event event, ActionState state)
                        throws Exception {
                    super.put(key, seq, action, event, state);
                    save(key, seq, action, event, state);
                }

                @Override
                public Object getRecoveryMarker(Map<?, Long> completed) {
                    return Map.of(0, nextMarker);
                }

                @Override
                public void rebuildState(List<Object> markers) {
                    if (markers.isEmpty()) {
                        return;
                    }
                    cache.clear();
                    try {
                        @SuppressWarnings("unchecked")
                        long start = ((Map<Integer, Long>) markers.get(0)).get(0);
                        List<org.apache.fluss.client.table.scanner.ScanRecord> records =
                                new ArrayList<>();
                        for (int i = (int) start; i < log.size(); i++) {
                            var entry = log.get(i);
                            var row =
                                    org.apache.fluss.row.GenericRow.of(
                                            org.apache.fluss.row.BinaryString.fromString(
                                                    entry.getKey()),
                                            entry.getValue());
                            var record =
                                    mock(org.apache.fluss.client.table.scanner.ScanRecord.class);
                            when(record.logOffset()).thenReturn((long) i);
                            when(record.getRow()).thenReturn(row);
                            records.add(record);
                        }
                        // Use the production Fluss record-replay loop; only scanner IO is replaced.
                        var method =
                                FlussActionStateStore.class.getDeclaredMethod(
                                        "replayRecords", Iterable.class, long.class);
                        method.setAccessible(true);
                        method.invoke(this, records, (long) log.size());
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
    }
}
