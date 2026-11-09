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
package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Preparation failures retain their durable positions through real tool dispatch and recovery. */
@Timeout(30)
public class SubagentPreparationRecoveryTest {
    private static String mode;
    private static boolean firstAttempt;
    private static final AtomicInteger PREPARATIONS = new AtomicInteger();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();
    private static final AtomicInteger POST_CALLS = new AtomicInteger();
    private static final AtomicInteger CHILD_CALLS = new AtomicInteger();

    @ParameterizedTest
    @CsvSource({
        "async-body,false",
        "async-body,true",
        "async-factory,false",
        "async-factory,true",
        "deferred-prepare,false",
        "deferred-prepare,true",
        "async-await,false",
        "async-await,true",
        "internal,false",
        "internal,true"
    })
    void replayKeepsCompletedSiblingAfterPreparationFailure(String scenario, boolean parallel)
            throws Exception {
        reset(scenario);
        Store store = new Store(true, false);
        Store recovered;
        OperatorSubtaskState checkpoint;
        try (var h = harness(parallel, store)) {
            h.open();
            checkpoint = h.snapshot(1L, 1L);
            Throwable crash = catchThrowable(() -> run(h));
            assertThat(crash).hasRootCauseMessage("crash after tool result persisted");
            assertThat(UncapturableException.isUncapturable(crash)).isTrue();
            assertThat(TOOL_CALLS).hasValue(1);
            recovered = store.copyWithoutFault();
            assertThat(store.toolSlots())
                    .singleElement()
                    .satisfies(
                            slots -> {
                                assertThat(slots).hasSize(scenario.equals("async-await") ? 3 : 2);
                                assertThat(slots.get(slots.size() - 1))
                                        .isEqualTo("tool-call:call-2");
                            });
        }
        int preparationsBeforeRecovery = PREPARATIONS.get();
        firstAttempt = false;
        try (var h = harness(parallel, recovered)) {
            h.initializeState(checkpoint);
            h.open();
            run(h);
            assertThat(h.getRecordOutput()).hasSize(1);
            assertThat(TOOL_CALLS).hasValue(1);
            assertThat(PREPARATIONS).hasValue(preparationsBeforeRecovery);
            if (scenario.equals("async-body")) {
                assertThat(POST_CALLS).hasValue(1);
            }
            if (scenario.equals("internal")) {
                assertThat(CHILD_CALLS).hasValue(1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"internal", "async-factory", "deferred-prepare"})
    void pendingPersistenceFailurePreventsPreparationAndSiblingExecution(String scenario)
            throws Exception {
        reset(scenario);
        Store store = new Store(false, true);
        try (var h = harness(true, store)) {
            h.open();
            Throwable failure = catchThrowable(() -> run(h));
            assertThat(UncapturableException.isUncapturable(failure)).isTrue();
            assertThat(failure).hasRootCauseMessage("pending write failed");
            assertThat(PREPARATIONS).hasValue(0);
            assertThat(TOOL_CALLS).hasValue(0);
            assertThat(POST_CALLS).hasValue(0);
        }
    }

    private static void reset(String scenario) {
        mode = scenario;
        firstAttempt = true;
        PREPARATIONS.set(0);
        TOOL_CALLS.set(0);
        POST_CALLS.set(0);
        CHILD_CALLS.set(0);
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            boolean parallel, Store store) throws Exception {
        Agent parent = new Agent();
        if (mode.equals("internal")) {
            Agent child = new Agent();
            child.addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    SubagentPreparationRecoveryTest.class.getMethod(
                            "child", Event.class, RunnerContext.class));
            parent.addResource(
                    "child",
                    ResourceType.AGENT,
                    new InternalSubagentResourceDescriptorBuilder()
                            .agent(child)
                            .inputSchema("{\"type\":\"object\"}")
                            .build());
        } else {
            Class<?> setup = mode.startsWith("async") ? AsyncSetup.class : DeferredSetup.class;
            parent.addResource(
                    "child",
                    ResourceType.AGENT,
                    ResourceDescriptor.Builder.newBuilder(setup.getName()).build());
        }
        parent.addResource(
                "ordinary",
                ResourceType.TOOL,
                Tool.fromMethod(SubagentPreparationRecoveryTest.class.getMethod("ordinary")));
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentPreparationRecoveryTest.class.getMethod(
                        "request", Event.class, RunnerContext.class));
        parent.addAction(
                new String[] {ToolResponseEvent.EVENT_TYPE},
                SubagentPreparationRecoveryTest.class.getMethod(
                        "receive", Event.class, RunnerContext.class));
        AgentConfiguration config = new AgentConfiguration();
        config.set(
                AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED,
                !ContinuationActionExecutor.isContinuationSupported());
        config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, parallel);
        config.set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, 10);
        AgentPlan compiled = new AgentPlan(parent, config);
        Map<String, Action> actions = new LinkedHashMap<>(compiled.getActions());
        actions.remove("chat_model_action");
        AgentPlan plan = new AgentPlan(actions, compiled.getResourceProviders(), config);
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<Long, Object>(plan, true, store),
                (KeySelector<Long, Long>) value -> 0L,
                TypeInformation.of(Long.class));
    }

    private static void run(KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> h)
            throws Exception {
        h.processElement(new StreamRecord<>(1L));
        ((ActionExecutionOperator<?, ?>) h.getOperator()).waitInFlightEventsFinished();
    }

    public static void request(Event event, RunnerContext ctx) {
        ctx.sendEvent(
                new ToolRequestEvent(
                        "model",
                        List.of(
                                new ToolCallBlock("call-1", "_subagent_child", Map.of()),
                                new ToolCallBlock("call-2", "ordinary", Map.of()))));
    }

    public static void receive(Event event, RunnerContext ctx) {
        ToolResponseEvent response = ToolResponseEvent.fromEvent(event);
        assertThat(response.getSuccess()).containsEntry("call-1", mode.equals("internal"));
        assertThat(response.getSuccess()).containsEntry("call-2", true);
        if (!mode.equals("internal")) {
            assertThat(response.getError().get("call-1")).contains("unavailable");
        }
        ctx.sendEvent(new OutputEvent("done"));
    }

    public static String ordinary() {
        TOOL_CALLS.incrementAndGet();
        return "tool-result";
    }

    public static void child(Event event, RunnerContext ctx) {
        CHILD_CALLS.incrementAndGet();
        ctx.sendEvent(new OutputEvent("child-result"));
    }

    public static class AsyncSetup extends BaseAsyncSubagentSetup {
        public AsyncSetup(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public String getInputSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        protected DurableCallable<Void> submitRequest(
                RunnerContext ctx, String sessionId, String callId, Object prompt) {
            ((RunnerContextImpl) ctx).checkMailboxThread();
            PREPARATIONS.incrementAndGet();
            if (mode.equals("async-factory") && firstAttempt) {
                throw new IllegalStateException("submit preparation unavailable");
            }
            return super.submitRequest(ctx, sessionId, callId, prompt);
        }

        @Override
        protected DurableCallable<SubagentResult> awaitResult(
                RunnerContext ctx, String sessionId, String callId) {
            ((RunnerContextImpl) ctx).checkMailboxThread();
            PREPARATIONS.incrementAndGet();
            if (mode.equals("async-await") && firstAttempt) {
                throw new IllegalStateException("await preparation unavailable");
            }
            return super.awaitResult(ctx, sessionId, callId);
        }

        @Override
        protected void callSubmitRequest(String sessionId, String callId, Object prompt) {
            POST_CALLS.incrementAndGet();
            if (mode.equals("async-body") && firstAttempt) {
                throw new IllegalStateException("remote submit unavailable");
            }
        }

        @Override
        protected RunStatus callQueryStatus(String sessionId, String callId) {
            return RunStatus.completed();
        }

        @Override
        protected SubagentResult callFetchResult(String sessionId, String callId) {
            return SubagentResult.ok("child-result");
        }
    }

    public static class DeferredSetup extends BaseDeferredSubagentSetup {
        public DeferredSetup(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public String getInputSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        protected DurableCallable<SubagentResult> prepare(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            ((RunnerContextImpl) ctx).checkMailboxThread();
            PREPARATIONS.incrementAndGet();
            if (firstAttempt) {
                throw new IllegalStateException("deferred preparation unavailable");
            }
            return new DurableCallable<>() {
                public String getId() {
                    return sessionId + "#" + callId;
                }

                public Class<SubagentResult> getResultClass() {
                    return SubagentResult.class;
                }

                public SubagentResult call() {
                    return SubagentResult.ok("child-result");
                }
            };
        }
    }

    static class Store extends InMemoryActionStateStore {
        final boolean failAfterTool;
        final boolean failPending;

        Store(boolean failAfterTool, boolean failPending) {
            super(false);
            this.failAfterTool = failAfterTool;
            this.failPending = failPending;
        }

        @Override
        public void put(Object key, long seq, Action action, Event event, ActionState state)
                throws IOException {
            if (failPending
                    && action.getName().equals("tool_call_action")
                    && state.getCallResults().stream().anyMatch(r -> r.isPending())) {
                throw new IOException("pending write failed");
            }
            super.put(key, seq, action, event, copy(state));
            if (failAfterTool
                    && action.getName().equals("tool_call_action")
                    && state.getCallResults().stream()
                            .anyMatch(
                                    r ->
                                            r.getFunctionId().equals("tool-call:call-2")
                                                    && r.isSuccess())) {
                throw new IOException("crash after tool result persisted");
            }
        }

        @Override
        public ActionState get(Object key, long seq, Action action, Event event)
                throws IOException {
            ActionState state = super.get(key, seq, action, event);
            return state == null ? null : copy(state);
        }

        Store copyWithoutFault() {
            Store result = new Store(false, false);
            getKeyedActionStates()
                    .forEach(
                            (key, states) -> {
                                Map<String, ActionState> copied = new HashMap<>();
                                states.forEach((id, state) -> copied.put(id, copy(state)));
                                result.getKeyedActionStates().put(key, copied);
                            });
            return result;
        }

        List<List<String>> toolSlots() {
            return getKeyedActionStates().values().stream()
                    .flatMap(m -> m.values().stream())
                    .filter(s -> s.getTaskEvent().getType().equals(ToolRequestEvent.EVENT_TYPE))
                    .map(
                            s ->
                                    s.getCallResults().stream()
                                            .map(r -> r.getFunctionId())
                                            .collect(Collectors.toList()))
                    .collect(Collectors.toList());
        }

        static ActionState copy(ActionState state) {
            return ActionStateSerde.deserialize(ActionStateSerde.serialize(state));
        }
    }
}
