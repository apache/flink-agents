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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentConfiguration;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovery of internal sub-agent calls.
 *
 * <p>A replayed parent action re-sends its call event rather than replaying a durable result, so
 * the child must not run again. That relies on the envelope being content-addressed by the
 * framework-assigned {@code (sessionId, callId)} identity: the replayed envelope carries the same
 * attributes, so the child action resolves to the action state persisted by the first run and
 * replays its recorded output.
 *
 * <p>Checkpoint tests restore a batch with completed, running, and queued calls, including nested
 * calls and multi-action children. The durable journal is serialized separately from Flink state,
 * matching the external ActionStateStore recovery boundary.
 *
 * <p>The replay case needs JDK 21+: a Java caller's wait must release the mailbox so the operator
 * can dispatch the child's actions, which only the continuation executor can do. The two envelope
 * cases are plain unit tests and run everywhere.
 */
public class InternalSubagentRecoveryTest {

    private static final String CHILD_SCOPE = "child";

    private static final AtomicInteger CHILD_EXECUTIONS = new AtomicInteger();
    private static final Map<String, Integer> CHAIN_STARTS = new ConcurrentHashMap<>();
    private static final AtomicInteger WAITING_CHILDREN = new AtomicInteger();
    private static CountDownLatch allowChildren;
    private static final AtomicInteger FAILED_CHILD_EXECUTIONS = new AtomicInteger();

    @BeforeEach
    void resetChildExecutions() {
        CHILD_EXECUTIONS.set(0);
        CHAIN_STARTS.clear();
        WAITING_CHILDREN.set(0);
        allowChildren = new CountDownLatch(1);
        FAILED_CHILD_EXECUTIONS.set(0);
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

    @SuppressWarnings("unused")
    public static void callChild(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(CHILD_SCOPE, ResourceType.AGENT);
        SubagentResult result = setup.submit(ctx, "p").await();
        ctx.sendEvent(new OutputEvent(result.getResult()));
    }

    /** A child whose first action completes before its second action waits. */
    public static class ChainedChild extends Agent {
        public ChainedChild() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    ChainedChild.class.getMethod("start", Event.class, RunnerContext.class));
            addAction(
                    new String[] {"child-next"},
                    ChainedChild.class.getMethod("finish", Event.class, RunnerContext.class));
        }

        public static void start(Event event, RunnerContext ctx) {
            String prompt = (String) InputEvent.fromEvent(event).getInput();
            CHAIN_STARTS.merge(prompt, 1, Integer::sum);
            ctx.sendEvent(new OutputEvent("start:" + prompt));
            ctx.sendEvent(new Event("child-next", Map.of("prompt", prompt)));
        }

        public static void finish(Event event, RunnerContext ctx) throws Exception {
            String prompt = (String) event.getAttr("prompt");
            if (!prompt.equals("fast")) {
                ctx.durableExecuteAsync(
                                new DurableCallable<String>() {
                                    @Override
                                    public String getId() {
                                        return "finish:" + prompt;
                                    }

                                    @Override
                                    public Class<String> getResultClass() {
                                        return String.class;
                                    }

                                    @Override
                                    public String call() throws Exception {
                                        WAITING_CHILDREN.incrementAndGet();
                                        if (!allowChildren.await(20, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "Child was not released");
                                        }
                                        return prompt;
                                    }
                                })
                        .await();
            }
            ctx.sendEvent(new OutputEvent("finish:" + prompt));
        }
    }

    public static class NestedChild extends Agent {
        public NestedChild() throws Exception {
            addResource("leaf", ResourceType.AGENT, new ChainedChild());
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    NestedChild.class.getMethod("delegate", Event.class, RunnerContext.class));
        }

        public static void delegate(Event event, RunnerContext ctx) throws Exception {
            SubagentSetup leaf = (SubagentSetup) ctx.getResource("leaf", ResourceType.AGENT);
            SubagentResult result =
                    leaf.submit(ctx, InputEvent.fromEvent(event).getInput()).await();
            for (Object output : (List<?>) result.getResult()) {
                ctx.sendEvent(new OutputEvent(output));
            }
        }
    }

    public static void callBatch(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(CHILD_SCOPE, ResourceType.AGENT);
        List<SubagentResult> results =
                setup.submit(ctx, "fast")
                        .combine(
                                setup.submit(ctx, "slow-one"),
                                setup.submit(ctx, "slow-two"),
                                setup.submit(ctx, "queued"))
                        .awaitAll();
        List<Object> outputs = new ArrayList<>();
        for (SubagentResult result : results) {
            assertThat(result.isSuccess()).isTrue();
            outputs.add(result.getResult());
        }
        ctx.sendEvent(new OutputEvent(outputs));
    }

    public static class FailingChild extends Agent {
        public FailingChild() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    FailingChild.class.getMethod("fail", Event.class, RunnerContext.class));
        }

        public static void fail(Event event, RunnerContext ctx) {
            FAILED_CHILD_EXECUTIONS.incrementAndGet();
            ctx.sendEvent(new Event("discarded-before-failure"));
            throw new IllegalArgumentException("child failed after sending an event");
        }
    }

    public static void callFailingChild(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource(CHILD_SCOPE, ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "failure").await();
        ctx.sendEvent(new OutputEvent(result.getErrorMessage()));
    }

    @Test
    @Timeout(30)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void checkpointBetweenChildFailureAndParentCompletionPreservesFailure() throws Exception {
        Agent agent = new Agent();
        agent.addResource(CHILD_SCOPE, ResourceType.AGENT, new FailingChild());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        "callFailingChild", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        InMemoryActionStateStore recoveredStore = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        String expectedError;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan, store)) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            while (FAILED_CHILD_EXECUTIONS.get() == 0) {
                harness.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
            }
            ActionState failedChild = childActionStates(store, 1L).values().iterator().next();
            assertThat(failedChild.isCompleted()).isTrue();
            expectedError = failedChild.getSubagentError();
            assertThat(expectedError).contains("child failed after sending an event");
            assertThat(harness.getRecordOutput()).isEmpty();
            snapshot = harness.snapshot(1L, 1L);
            copyJournal(store, recoveredStore);
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly(expectedError);
        }
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                harness(plan, recoveredStore)) {
            restored.initializeState(snapshot);
            restored.open();
            ((ActionExecutionOperator<Long, Object>) restored.getOperator())
                    .waitInFlightEventsFinished();
            assertThat(restored.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly(expectedError);
            assertThat(FAILED_CHILD_EXECUTIONS.get()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void checkpointRestoresCompletedRunningAndQueuedCalls(boolean nested) throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                CHILD_SCOPE, ResourceType.AGENT, nested ? new NestedChild() : new ChainedChild());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentRecoveryTest.class.getMethod(
                        "callBatch", Event.class, RunnerContext.class));
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.SUBAGENT_PARALLELISM, 2);
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);
        AgentPlan plan = new AgentPlan(agent, config);
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        InMemoryActionStateStore recoveredStore = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan, store)) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (WAITING_CHILDREN.get() != 2 && System.nanoTime() < deadline) {
                harness.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
            }
            assertThat(WAITING_CHILDREN.get()).isEqualTo(2);
            assertThat(CHAIN_STARTS).containsOnlyKeys("fast", "slow-one", "slow-two");
            ActionState parent =
                    store.getKeyedActionStates().get(1L).values().stream()
                            .filter(
                                    state ->
                                            !(state.getTaskEvent()
                                                    instanceof InternalSubagentCallEvent))
                            .findFirst()
                            .orElseThrow();
            assertThat(parent.getCallResults()).hasSize(4);
            assertThat(parent.getCallResults().get(0).isPending()).isFalse();
            assertThat(parent.getCallResults().subList(1, 4)).allMatch(call -> call.isPending());
            // A checkpoint may observe either queue rotation. Pin the child-first rotation so
            // restoring a child before its transient scope exists is a deterministic regression.
            OperatorStateManager stateManager =
                    ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                            .getOperatorStateManager();
            List<ActionTask> queuedTasks = new ArrayList<>();
            ActionTask queuedTask;
            while ((queuedTask = stateManager.pollNextActionTask()) != null) {
                queuedTasks.add(queuedTask);
            }
            queuedTasks.sort(
                    (left, right) ->
                            Boolean.compare(right.isSubagentEvent(), left.isSubagentEvent()));
            for (ActionTask task : queuedTasks) {
                stateManager.addActionTask(task);
            }
            snapshot = harness.snapshot(1L, 1L);
            copyJournal(store, recoveredStore);
        } finally {
            allowChildren.countDown();
        }

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                harness(plan, recoveredStore)) {
            restored.initializeState(snapshot);
            restored.open();
            ((ActionExecutionOperator<Long, Object>) restored.getOperator())
                    .waitInFlightEventsFinished();
            assertThat(restored.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly(
                            List.of(
                                    List.of("start:fast", "finish:fast"),
                                    List.of("start:slow-one", "finish:slow-one"),
                                    List.of("start:slow-two", "finish:slow-two"),
                                    List.of("start:queued", "finish:queued")));
            assertThat(CHAIN_STARTS)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("fast", 1, "slow-one", 1, "slow-two", 1, "queued", 1));
        }
    }

    @Test
    @Timeout(60)
    @EnabledForJreRange(min = JRE.JAVA_21)
    void replayedCallResolvesToTheChildActionStateInsteadOfRunningAgain() throws Exception {
        long key = 1L;

        // Stage 1: a full run persists the child's action state.
        InMemoryActionStateStore store1 = new InMemoryActionStateStore(false);
        run(plan(), store1, key);

        assertThat(run(plan(), store1, key)).hasSize(1);
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

    private static void copyJournal(
            InMemoryActionStateStore source, InMemoryActionStateStore target) {
        source.getKeyedActionStates()
                .forEach(
                        (key, states) -> {
                            Map<String, ActionState> copies = new LinkedHashMap<>();
                            states.forEach(
                                    (id, state) ->
                                            copies.put(
                                                    id,
                                                    ActionStateSerde.deserialize(
                                                            ActionStateSerde.serialize(state))));
                            target.getKeyedActionStates().put(key, copies);
                        });
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            AgentPlan plan, InMemoryActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true, store),
                (KeySelector<Long, Long>) value -> value,
                TypeInformation.of(Long.class));
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
