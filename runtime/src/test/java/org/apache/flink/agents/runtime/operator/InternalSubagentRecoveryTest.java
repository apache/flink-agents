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
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Recovery of internal sub-agent calls.
 *
 * <p>A replayed parent action re-sends its call event rather than replaying a durable result, so
 * the child must not run again. That relies on the envelope being content-addressed by the
 * framework-assigned {@code (sessionId, callId)} identity: the replayed envelope carries the same
 * attributes, so the child action resolves to the action state persisted by the first run and
 * replays its recorded output.
 *
 * <p>The crash window under test is "child completed and persisted, parent not yet complete". It is
 * reproduced by carrying the child's action states into a fresh store while dropping the parent's,
 * then re-processing the identical record; the sub-agent manager and the identity context are
 * heap-only, so the second run rebuilds both from scratch.
 *
 * <p>The replay case needs JDK 21+: a Java caller's wait must release the mailbox so the operator
 * can dispatch the child's actions, which only the continuation executor can do. The two envelope
 * cases are plain unit tests and run everywhere.
 */
public class InternalSubagentRecoveryTest {

    private static final String CHILD_SCOPE = "child";

    private static final AtomicInteger CHILD_EXECUTIONS = new AtomicInteger();

    @BeforeEach
    void resetChildExecutions() {
        CHILD_EXECUTIONS.set(0);
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

    @Test
    @Timeout(60)
    void replayedCallResolvesToTheChildActionStateInsteadOfRunningAgain() throws Exception {
        assumeTrue(
                Runtime.version().feature() >= 21,
                "a Java caller's sub-agent wait needs continuations (JDK 21+)");
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
