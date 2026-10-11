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
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.subagent.InternalSubagentCallEvent;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovery tests for internal sub-agent calls across queued, suspended, completed, and nested child
 * actions. JDK 21+ is required for cases in which the caller or child suspends while asynchronous
 * work proceeds through the mailbox.
 */
public class InternalSubagentRecoveryTest {

    private static final String CHILD_SCOPE = "child";
    private static final String LEAF_SCOPE = "leaf";
    private static final String CHAIN_EVENT = "chain";

    private static final AtomicInteger CHILD_EXECUTIONS = new AtomicInteger();
    private static final AtomicInteger FIRST_DURABLE_EXECUTIONS = new AtomicInteger();
    private static final AtomicInteger SECOND_DURABLE_EXECUTIONS = new AtomicInteger();
    private static final AtomicInteger CALLER_TAIL_EXECUTIONS = new AtomicInteger();
    private static final AtomicInteger CHAIN_HEAD_EXECUTIONS = new AtomicInteger();
    private static final AtomicInteger CHAIN_TAIL_EXECUTIONS = new AtomicInteger();
    private static CountDownLatch firstDurableStarted;
    private static CountDownLatch firstDurableGate;
    private static CountDownLatch secondDurableStarted;
    private static CountDownLatch secondDurableGate;
    private static CountDownLatch callerTailStarted;
    private static CountDownLatch callerTailGate;
    private static CountDownLatch chainHeadCompleted;

    @BeforeEach
    void resetChildExecutions() {
        CHILD_EXECUTIONS.set(0);
        FIRST_DURABLE_EXECUTIONS.set(0);
        SECOND_DURABLE_EXECUTIONS.set(0);
        CALLER_TAIL_EXECUTIONS.set(0);
        CHAIN_HEAD_EXECUTIONS.set(0);
        CHAIN_TAIL_EXECUTIONS.set(0);
        firstDurableStarted = new CountDownLatch(0);
        firstDurableGate = new CountDownLatch(0);
        secondDurableStarted = new CountDownLatch(0);
        secondDurableGate = new CountDownLatch(0);
        callerTailStarted = new CountDownLatch(0);
        callerTailGate = new CountDownLatch(0);
        chainHeadCompleted = new CountDownLatch(0);
    }

    /** Child agent counting how many times its action body actually ran. */
    public static class ChildAgent extends Agent {

        public ChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    ChildAgent.class.getMethod("handle", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void handle(Event event, RunnerContext ctx) {
            CHILD_EXECUTIONS.incrementAndGet();
            ctx.sendEvent(new OutputEvent("child:" + InputEvent.fromEvent(event).getInput()));
        }
    }

    public static class DurableChildAgent extends Agent {

        public DurableChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    DurableChildAgent.class.getMethod("handle", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void handle(Event event, RunnerContext ctx) throws Exception {
            CHILD_EXECUTIONS.incrementAndGet();
            String first =
                    ctx.durableExecuteAsync(
                                    durableCall(
                                            "first",
                                            FIRST_DURABLE_EXECUTIONS,
                                            firstDurableStarted,
                                            firstDurableGate))
                            .await();
            ctx.sendEvent(new OutputEvent(first));
            String second =
                    ctx.durableExecuteAsync(
                                    durableCall(
                                            "second",
                                            SECOND_DURABLE_EXECUTIONS,
                                            secondDurableStarted,
                                            secondDurableGate))
                            .await();
            ctx.sendEvent(new OutputEvent(second));
        }
    }

    public static class ChainedChildAgent extends Agent {

        public ChainedChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    ChainedChildAgent.class.getMethod(
                            "emitIntermediate", Event.class, RunnerContext.class));
            addAction(
                    new String[] {CHAIN_EVENT},
                    ChainedChildAgent.class.getMethod(
                            "completeChain", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void emitIntermediate(Event event, RunnerContext ctx) throws Exception {
            CHAIN_HEAD_EXECUTIONS.incrementAndGet();
            ctx.sendEvent(new Event(CHAIN_EVENT));
            ctx.durableExecuteAsync(
                            durableCall(
                                    "chain-head",
                                    FIRST_DURABLE_EXECUTIONS,
                                    firstDurableStarted,
                                    firstDurableGate))
                    .await();
            chainHeadCompleted.countDown();
        }

        @SuppressWarnings("unused")
        public static void completeChain(Event event, RunnerContext ctx) {
            CHAIN_TAIL_EXECUTIONS.incrementAndGet();
            ctx.sendEvent(new OutputEvent("chain-complete"));
        }
    }

    /** Child that delegates once more, exercising nested recovery routing. */
    public static class ParentAgent extends Agent {

        public ParentAgent() throws Exception {
            addResource(LEAF_SCOPE, ResourceType.AGENT, new ChildAgent());
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    InternalSubagentRecoveryTest.class.getMethod(
                            "callLeaf", Event.class, RunnerContext.class));
        }
    }

    @SuppressWarnings("unused")
    public static void callChild(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(CHILD_SCOPE, ResourceType.AGENT);
        SubagentResult result = setup.submit(ctx, "p").await();
        ctx.sendEvent(new OutputEvent(immutableSnapshot(result.getResult())));
    }

    @SuppressWarnings("unused")
    public static void callLeaf(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(LEAF_SCOPE, ResourceType.AGENT);
        SubagentResult result = setup.submit(ctx, InputEvent.fromEvent(event).getInput()).await();
        ctx.sendEvent(new OutputEvent(immutableSnapshot(result.getResult())));
    }

    @SuppressWarnings("unused")
    public static void callChildThenWait(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(CHILD_SCOPE, ResourceType.AGENT);
        SubagentResult result = setup.submit(ctx, "p").await();
        ctx.durableExecuteAsync(
                        durableCall(
                                "caller-tail",
                                CALLER_TAIL_EXECUTIONS,
                                callerTailStarted,
                                callerTailGate))
                .await();
        ctx.sendEvent(new OutputEvent(immutableSnapshot(result.getResult())));
    }

    private static DurableCallable<String> durableCall(
            String id, AtomicInteger executions, CountDownLatch started, CountDownLatch gate) {
        return new DurableCallable<String>() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Class<String> getResultClass() {
                return String.class;
            }

            @Override
            public String call() throws Exception {
                executions.incrementAndGet();
                started.countDown();
                gate.await();
                return id;
            }
        };
    }

    @Test
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void replayedCallResolvesToTheChildActionStateInsteadOfRunningAgain() throws Exception {
        long key = 1L;

        // Stage 1: a full run persists the child's action state.
        InMemoryActionStateStore store1 = new InMemoryActionStateStore(false);
        run(plan(), store1, key);

        assertThat(CHILD_EXECUTIONS.get()).isEqualTo(1);
        Map<String, ActionState> childStates = childActionStates(store1, key);
        assertThat(childStates).hasSize(1);
        assertThat(childStates.values().iterator().next().getSubagentResultEvents()).hasSize(1);

        // Stage 2: keep only the child's action state, so the parent action replays its body and
        // re-sends the call event.
        InMemoryActionStateStore store2 = new InMemoryActionStateStore(false);
        // The store is keyed by the typed Flink key, a Long here.
        store2.getKeyedActionStates().put(key, new LinkedHashMap<>(childStates));

        List<StreamRecord<Object>> output = run(plan(), store2, key);

        assertThat(CHILD_EXECUTIONS.get())
                .as("the replayed call must reuse the persisted child action state")
                .isEqualTo(1);
        assertThat(childActionStates(store2, key).keySet())
                .as("the replayed envelope must address the same action state")
                .isEqualTo(childStates.keySet());
        assertThat(output).hasSize(1);
    }

    @ParameterizedTest(name = "parentFirst={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void dropsPendingChildAndReplaysItFromTheRoot(boolean parentFirst) throws Exception {
        AgentPlan agentPlan = plan();
        Checkpoint checkpoint;
        try (Attempt original = Attempt.start(agentPlan, false)) {
            original.queue("handle", "callChild");
            if (parentFirst) {
                original.rotateFirstTask();
                original.queue("callChild", "handle");
            }
            checkpoint = original.snapshot(1L);
        }

        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.queue("callChild");
            restored.finish();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(1);
            assertThat(restored.harness.getRecordOutput()).hasSize(1);
        }
    }

    @ParameterizedTest(name = "actionState={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void restoresAfterChildCompletesBeforeCallerResumes(boolean actionStateEnabled)
            throws Exception {
        AgentPlan agentPlan = plan();
        Checkpoint checkpoint;
        try (Attempt original = Attempt.start(agentPlan, actionStateEnabled)) {
            original.queue("handle", "callChild");
            original.nextMail();
            original.queue("callChild");
            checkpoint = original.snapshot(1L);
        }

        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.queue("callChild");
            restored.finish();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(actionStateEnabled ? 1 : 2);
            restored.assertSingleOutput(List.of("child:p"));
        }
    }

    @Test
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void replaysCompletedRootCallerWithoutRebootstrappingItsFinishedChildCall() throws Exception {
        AgentPlan agentPlan = plan();
        Checkpoint checkpoint;
        // Suspend the root caller at its child call so the operator state keeps the root task
        // queued while the child has already completed.
        try (Attempt original = Attempt.start(agentPlan, true)) {
            original.queue("handle", "callChild");
            original.nextMail();
            original.queue("callChild");
            checkpoint = original.snapshot(1L);
        }

        // Model the race where the root finishes in the same dispatch that emitted its bootstrap
        // envelope, so the envelope is persisted among the completed root's output events. Seed
        // that state deterministically rather than depending on the timing.
        ActionState rootState = rootCallerActionState(checkpoint.store, 1L);
        rootState.getOutputEvents().clear();
        rootState.addEvent(
                InternalSubagentCallEvent.bootstrap(
                        new InputEvent("p"), CHILD_SCOPE, "s-replay#c-replay", "s-replay"));
        rootState.addEvent(new OutputEvent(List.of("child:p")));
        rootState.markCompleted();

        // Restore re-dispatches the queued root, sees it completed, and replays its persisted
        // outputs. The finished child call's bootstrap envelope must be dropped: that call has long
        // completed and registers no call status, so replaying it would abort the run.
        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.queue("callChild");
            restored.finish();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(1);
            restored.assertSingleOutput(List.of("child:p"));
        }
    }

    @Test
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void restoresDownstreamActionAfterTriggerActionSuspendsAndCompletes() throws Exception {
        firstDurableStarted = new CountDownLatch(1);
        firstDurableGate = new CountDownLatch(1);
        chainHeadCompleted = new CountDownLatch(1);
        AgentPlan agentPlan = chainedChildPlan();
        Checkpoint checkpoint;
        try (Attempt original = Attempt.start(agentPlan, true)) {
            try {
                original.advanceUntil(firstDurableStarted);
                assertThat(original.tasks())
                        .extracting(task -> task.getAction().getName())
                        .doesNotContain("completeChain");
                assertThat(CHAIN_TAIL_EXECUTIONS.get()).isZero();
                firstDurableGate.countDown();
                original.advanceUntil(chainHeadCompleted);
                assertThat(CHAIN_HEAD_EXECUTIONS.get()).isEqualTo(1);
                assertThat(CHAIN_TAIL_EXECUTIONS.get()).isZero();
                checkpoint = original.snapshot(1L);
            } finally {
                firstDurableGate.countDown();
            }
        }

        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.finish();
            assertThat(CHAIN_HEAD_EXECUTIONS.get()).isEqualTo(1);
            assertThat(FIRST_DURABLE_EXECUTIONS.get()).isEqualTo(1);
            assertThat(CHAIN_TAIL_EXECUTIONS.get()).isEqualTo(1);
            restored.assertSingleOutput(List.of("chain-complete"));
        }
    }

    @ParameterizedTest(name = "actionState={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void restoresPersistedDurableCallAndPartialOutput(boolean actionStateEnabled) throws Exception {
        secondDurableStarted = new CountDownLatch(1);
        secondDurableGate = new CountDownLatch(1);
        AgentPlan agentPlan = durableChildPlan("callChild");
        Checkpoint checkpoint;
        try (Attempt original = Attempt.start(agentPlan, actionStateEnabled)) {
            try {
                original.queue("handle", "callChild");
                original.nextMail();
                original.advanceUntil(secondDurableStarted);
                checkpoint = original.snapshot(1L);
            } finally {
                secondDurableGate.countDown();
            }
        }

        secondDurableGate = new CountDownLatch(0);
        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.finish();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(2);
            assertThat(FIRST_DURABLE_EXECUTIONS.get()).isEqualTo(actionStateEnabled ? 1 : 2);
            assertThat(SECOND_DURABLE_EXECUTIONS.get()).isEqualTo(2);
            restored.assertSingleOutput(List.of("first", "second"));
        }
    }

    @ParameterizedTest(name = "actionState={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void restoresCallerAfterChildResultBeforeCallerCompletion(boolean actionStateEnabled)
            throws Exception {
        callerTailStarted = new CountDownLatch(1);
        callerTailGate = new CountDownLatch(1);
        AgentPlan agentPlan = callerTailPlan();
        Checkpoint checkpoint;
        try (Attempt original = Attempt.start(agentPlan, actionStateEnabled)) {
            try {
                original.queue("handle", "callChildThenWait");
                original.nextMail();
                original.queue("callChildThenWait");
                original.advanceUntil(callerTailStarted);
                checkpoint = original.snapshot(1L);
            } finally {
                callerTailGate.countDown();
            }
        }

        callerTailGate = new CountDownLatch(0);
        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.finish();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(actionStateEnabled ? 1 : 2);
            assertThat(CALLER_TAIL_EXECUTIONS.get()).isEqualTo(2);
            restored.assertSingleOutput(List.of("child:p"));
        }
    }

    @Test
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void restoresNestedCallTreeFromTheRoot() throws Exception {
        AgentPlan agentPlan = nestedPlan();
        Checkpoint checkpoint;
        try (Attempt original = Attempt.start(agentPlan, true)) {
            original.nextMail();
            checkpoint = original.snapshot(1L);
        }

        try (Attempt restored = Attempt.restore(agentPlan, checkpoint)) {
            restored.finish();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(1);
            restored.assertSingleOutput(List.of(List.of("child:p")));
        }
    }

    @Test
    void envelopeSurvivesActionStateSerde() {
        InternalSubagentCallEvent envelope =
                InternalSubagentCallEvent.bootstrap(
                        new InputEvent("p"), CHILD_SCOPE, "s-1#c-1", "s-1");

        ActionState recovered =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(new ActionState(envelope)));

        assertThat(recovered.getTaskEvent()).isInstanceOf(InternalSubagentCallEvent.class);
        InternalSubagentCallEvent recoveredEnvelope =
                (InternalSubagentCallEvent) recovered.getTaskEvent();
        assertThat(recoveredEnvelope.getSessionId()).isEqualTo("s-1");
        assertThat(recoveredEnvelope.getCallId()).isEqualTo("s-1#c-1");
        assertThat(recoveredEnvelope.getTargetScope()).isEqualTo(CHILD_SCOPE);
        assertThat(recoveredEnvelope.getDelegateEventType()).isEqualTo(InputEvent.EVENT_TYPE);
        assertThat(recoveredEnvelope.getDelegate().getAttributes())
                .isEqualTo(envelope.getDelegate().getAttributes());
    }

    @Test
    void distinctCallsDoNotShareAnActionState() {
        InputEvent delegate = new InputEvent("p");
        InternalSubagentCallEvent first =
                InternalSubagentCallEvent.bootstrap(delegate, CHILD_SCOPE, "s-1#c-1", "s-1");
        InternalSubagentCallEvent second =
                InternalSubagentCallEvent.bootstrap(delegate, CHILD_SCOPE, "s-1#c-2", "s-1");

        assertThat(first.getAttributes()).isNotEqualTo(second.getAttributes());
    }

    // Helpers

    /**
     * Returns a deeply immutable snapshot of a sub-agent result payload for emission as operator
     * output.
     *
     * <p>The operator output type is {@code Object}, so the test harness copies every emitted value
     * through a Kryo serializer it derives from the value itself. On the JDK 21 leg, which the root
     * pom's surefire {@code argLine} runs with {@code --add-opens=java.base/java.util=ALL-UNNAMED}
     * (required for JDK 21 CI), Kryo builds a mutable {@link java.util.ArrayList} copy through
     * Objenesis, so its {@code elementData} stays null and the first {@code add} throws a {@link
     * NullPointerException}. Immutable lists instead use Kryo's built-in immutable-collection
     * serializer and copy cleanly. {@link SubagentResult#getResult()} hands back a mutable list
     * both on the first run and, via Jackson, on replay, so callers emit this snapshot instead;
     * every recovery assertion stays intact because the snapshot preserves the same elements and
     * nesting.
     */
    private static Object immutableSnapshot(Object value) {
        if (value instanceof List) {
            return ((List<?>) value)
                    .stream()
                            .map(InternalSubagentRecoveryTest::immutableSnapshot)
                            .collect(Collectors.toUnmodifiableList());
        }
        return value;
    }

    private static AgentPlan plan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(CHILD_SCOPE, ResourceType.AGENT, new ChildAgent());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        "callChild", Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    private static AgentPlan durableChildPlan(String callerAction) throws Exception {
        Agent agent = new Agent();
        agent.addResource(CHILD_SCOPE, ResourceType.AGENT, new DurableChildAgent());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        callerAction, Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    private static AgentPlan chainedChildPlan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(CHILD_SCOPE, ResourceType.AGENT, new ChainedChildAgent());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        "callChild", Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    private static AgentPlan callerTailPlan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(CHILD_SCOPE, ResourceType.AGENT, new ChildAgent());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        "callChildThenWait", Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    private static AgentPlan nestedPlan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(CHILD_SCOPE, ResourceType.AGENT, new ParentAgent());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        "callChild", Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    /** The action states whose triggering event is a sub-agent call envelope. */
    private static Map<String, ActionState> childActionStates(
            InMemoryActionStateStore store, long key) {
        Map<String, ActionState> states = store.getKeyedActionStates().getOrDefault(key, Map.of());
        return states.entrySet().stream()
                .filter(e -> e.getValue().getTaskEvent() instanceof InternalSubagentCallEvent)
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (a, b) -> a,
                                LinkedHashMap::new));
    }

    /** The action state whose triggering event is not a sub-agent envelope: the root caller's. */
    private static ActionState rootCallerActionState(InMemoryActionStateStore store, long key) {
        return store.getKeyedActionStates().getOrDefault(key, Map.of()).values().stream()
                .filter(state -> !(state.getTaskEvent() instanceof InternalSubagentCallEvent))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no root caller action state seeded for key " + key));
    }

    private static InMemoryActionStateStore copyStore(InMemoryActionStateStore source) {
        if (source == null) {
            return null;
        }
        InMemoryActionStateStore copy = new InMemoryActionStateStore(false);
        source.getKeyedActionStates()
                .forEach(
                        (key, states) -> {
                            Map<String, ActionState> copiedStates = new LinkedHashMap<>();
                            states.forEach(
                                    (id, state) ->
                                            copiedStates.put(
                                                    id,
                                                    ActionStateSerde.deserialize(
                                                            ActionStateSerde.serialize(state))));
                            copy.getKeyedActionStates().put(key, copiedStates);
                        });
        return copy;
    }

    private static final class Checkpoint {
        private final OperatorSubtaskState state;
        private final InMemoryActionStateStore store;

        private Checkpoint(OperatorSubtaskState state, InMemoryActionStateStore store) {
            this.state = state;
            this.store = store;
        }
    }

    private static final class Attempt implements AutoCloseable {
        private final KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness;
        private final InMemoryActionStateStore store;

        private Attempt(AgentPlan plan, InMemoryActionStateStore store) throws Exception {
            this.store = store;
            this.harness =
                    new KeyedOneInputStreamOperatorTestHarness<>(
                            new ActionExecutionOperatorFactory<>(plan, true, store),
                            (KeySelector<Long, Long>) value -> value,
                            TypeInformation.of(Long.class));
        }

        private static Attempt start(AgentPlan plan, boolean actionStateEnabled) throws Exception {
            Attempt attempt =
                    new Attempt(
                            plan, actionStateEnabled ? new InMemoryActionStateStore(false) : null);
            attempt.harness.open();
            attempt.harness.processElement(new StreamRecord<>(1L));
            attempt.nextMail();
            return attempt;
        }

        private static Attempt restore(AgentPlan plan, Checkpoint checkpoint) throws Exception {
            Attempt attempt = new Attempt(plan, checkpoint.store);
            attempt.harness.initializeState(checkpoint.state);
            attempt.harness.open();
            return attempt;
        }

        @SuppressWarnings("unchecked")
        private ActionExecutionOperator<Long, Object> operator() {
            return (ActionExecutionOperator<Long, Object>) harness.getOperator();
        }

        private List<ActionTask> tasks() throws Exception {
            List<ActionTask> tasks = new ArrayList<>();
            operator()
                    .getOperatorStateManager()
                    .forEachActionTaskKey(
                            operator().getKeyedStateBackend(),
                            (key, state) -> state.get().forEach(tasks::add));
            return tasks;
        }

        private List<ActionTask> queue(String... actionNames) throws Exception {
            List<ActionTask> tasks = tasks();
            assertThat(tasks)
                    .extracting(task -> task.getAction().getName())
                    .containsExactly(actionNames);
            return tasks;
        }

        private void rotateFirstTask() throws Exception {
            operator().setCurrentKey(1L);
            OperatorStateManager state = operator().getOperatorStateManager();
            ActionTask first = state.pollNextActionTask();
            assertThat(first).isNotNull();
            state.addActionTask(first);
        }

        private Checkpoint snapshot(long checkpointId) throws Exception {
            return new Checkpoint(harness.snapshot(checkpointId, checkpointId), copyStore(store));
        }

        private void nextMail() throws Exception {
            TaskMailbox mailbox = harness.getTaskMailbox();
            assertThat(mailbox.size()).isPositive();
            mailbox.take(TaskMailbox.MIN_PRIORITY).run();
        }

        private void advanceUntil(CountDownLatch latch) throws Exception {
            while (!latch.await(10, TimeUnit.MILLISECONDS)) {
                nextMail();
            }
        }

        @SuppressWarnings("unchecked")
        private void assertSingleOutput(Object expected) {
            List<StreamRecord<Object>> output =
                    (List<StreamRecord<Object>>) harness.getRecordOutput();
            assertThat(output).hasSize(1);
            assertThat(output.get(0).getValue()).isEqualTo(expected);
        }

        private void finish() throws Exception {
            while (operator().getOperatorStateManager().hasProcessingKeys()
                    || harness.getTaskMailbox().size() > 0) {
                nextMail();
            }
        }

        @Override
        public void close() throws Exception {
            harness.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<StreamRecord<Object>> run(
            AgentPlan plan, InMemoryActionStateStore store, long key) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true, store),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(key));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            return (List<StreamRecord<Object>>) harness.getRecordOutput();
        }
    }
}
