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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.subagent.InternalSubagentCallEvent;
import org.apache.flink.agents.runtime.subagent.InternalSubagentSetup;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the actual scoped ReAct chat/tool loop without an external model. */
@Timeout(30)
public class ReActSubagentTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();
    private static boolean structured;

    public static class Answer {
        public String answer;
    }

    @BeforeEach
    void reset() {
        assumeTrue(Runtime.version().feature() >= 21, "Internal Java calls need continuations");
        CALLS.clear();
        TOOL_CALLS.set(0);
        structured = false;
    }

    public static class ScriptedSetup extends BaseChatModelSetup {
        public ScriptedSetup(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>(Map.of("model", getModel()));
        }
    }

    public static class ScriptedConnection extends BaseChatModelConnection {
        public ScriptedConnection(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> params) {
            String model = (String) params.get("model");
            ChatMessage last = messages.get(messages.size() - 1);
            CALLS.add(model + ":" + last.getRole().getValue());
            if (last.getRole() == MessageRole.TOOL) {
                assertThat(last.getText())
                        .contains(model.equals("child") ? "child evidence" : "child answer");
                if (structured && model.equals("parent")) {
                    assertThat(last.getText()).isEqualTo("[{\"answer\":\"child answer\"}]");
                }
                return new ChatMessage(
                        MessageRole.ASSISTANT,
                        structured && model.equals("child")
                                ? "{\"answer\":\"child answer\"}"
                                : model + " answer");
            }
            if (model.equals("child")) {
                assertThat(messages)
                        .extracting(ChatMessage::getText)
                        .doesNotContain("PARENT_ONLY_SCHEMA");
                assertThat(messages.get(0).getText()).isEqualTo("Literal {prompt} instructions");
                assertThat(last.getText()).isEqualTo("investigate");
                assertThat(tools).extracting(Tool::getName).containsExactly("evidence");
                return request("evidence", Map.of());
            }
            assertThat(tools).extracting(Tool::getName).containsExactly("_subagent_researcher");
            assertThat(tools.get(0).getDescription()).contains("Research a task");
            return request("_subagent_researcher", Map.of("prompt", "investigate"));
        }
    }

    public static class ChildTools {
        @org.apache.flink.agents.api.annotation.Tool(description = "Child evidence")
        public static String evidence() {
            TOOL_CALLS.incrementAndGet();
            return "child evidence";
        }
    }

    public static class ParentTools {
        @org.apache.flink.agents.api.annotation.Tool(description = "Parent evidence")
        public static String evidence() {
            return "parent evidence";
        }
    }

    private static ChatMessage request(String name, Map<String, Object> arguments) {
        return ChatMessage.assistant(
                "",
                List.of(
                        Map.of(
                                "id",
                                "call-1",
                                "type",
                                "function",
                                "function",
                                Map.of("name", name, "arguments", arguments))));
    }

    private static ResourceDescriptor model(String name) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(ScriptedSetup.class.getName())
                        .addInitialArgument("connection", "connection")
                        .addInitialArgument("model", name);
        if (name.equals("child")) {
            builder.addInitialArgument("tools", List.of("evidence"));
        } else {
            builder.addInitialArgument("subagents", List.of("researcher"));
        }
        return builder.build();
    }

    private static Agent child() throws Exception {
        Agent child =
                ReActAgent.forSubagent(
                        model("child"),
                        "Research a task",
                        "Literal {prompt} instructions",
                        structured ? Answer.class : null);
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActSubagentTest.class.getMethod(
                        "assertChildScope", Event.class, RunnerContext.class));
        child.addResource(
                "evidence",
                ResourceType.TOOL,
                Tool.fromMethod(ChildTools.class.getMethod("evidence")));
        return child;
    }

    private static void resources(Agent parent) throws Exception {
        parent.addResource(
                "connection",
                ResourceType.CHAT_MODEL_CONNECTION,
                new ResourceDescriptor(ScriptedConnection.class.getName(), Map.of()));
        parent.addResource(
                "evidence",
                ResourceType.TOOL,
                Tool.fromMethod(ParentTools.class.getMethod("evidence")));
        parent.addResource("researcher", ResourceType.AGENT, child());
    }

    public static void assertChildScope(Event event, RunnerContext ctx) throws Exception {
        assertThat(ctx.getSensoryMemory().isExist("parent_secret")).isFalse();
        assertThat(ctx.getSensoryMemory().isExist("child_marker")).isFalse();
        ctx.getSensoryMemory().set("child_marker", "private");
    }

    public static void invokeTwice(Event event, RunnerContext ctx) throws Exception {
        invoke(event, ctx);
        invoke(event, ctx);
    }

    public static void invoke(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("researcher", ResourceType.AGENT);
        ctx.getSensoryMemory().set("parent_secret", "private");
        Object input = InputEvent.fromEvent(event).getInput();
        SubagentResult result =
                child.submit(
                                ctx,
                                input.equals(2L)
                                        ? Map.of("prompt", 42)
                                        : Map.of("prompt", "investigate"))
                        .await();
        assertThat(ctx.getSensoryMemory().isExist("child_marker")).isFalse();
        assertThat(ctx.getSensoryMemory().isExist("_TOOL_CALL_CONTEXT")).isFalse();
        ctx.sendEvent(
                new OutputEvent(
                        result.isSuccess() ? result.getResult() : result.getErrorMessage()));
    }

    private static Agent explicitParent() throws Exception {
        Agent parent = new Agent();
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActSubagentTest.class.getMethod("invoke", Event.class, RunnerContext.class));
        resources(parent);
        return parent;
    }

    @Test
    void concurrentCallsCompleteWithOneAsyncWorker() throws Exception {
        AgentPlan plan = new AgentPlan(explicitParent());
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                checkpointHarness(plan, new SnapshotStore())) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            harness.processElement(new StreamRecord<>(3L));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput()).hasSize(2);
            assertThat(TOOL_CALLS.get()).isEqualTo(2);
        }
    }

    @Test
    void plainChildDoesNotInheritParentOutputSchemaPrompt() throws Exception {
        Agent parent = explicitParent();
        parent.addResource(
                "_default_schema_prompt",
                ResourceType.PROMPT,
                Prompt.fromText("PARENT_ONLY_SCHEMA"));
        assertThat(run(new AgentPlan(parent), 1L)).containsExactly(List.of("child answer"));
    }

    @Test
    void explicitCallRunsTheChildToolLoopAfterPlanReconstruction() throws Exception {
        assertThat(run(new AgentPlan(explicitParent()), 1L))
                .containsExactly(List.of("child answer"));
        assertThat(CALLS).containsExactly("child:user", "child:tool");
    }

    @Test
    void modelDelegationResumesTheParentWithTheChildAnswer() throws Exception {
        Agent parent = new ReActAgent(model("parent"), null, null);
        resources(parent);
        assertThat(run(new AgentPlan(parent), 1L)).containsExactly("parent answer");
        assertThat(CALLS).containsExactly("parent:user", "child:user", "child:tool", "parent:tool");
    }

    @Test
    void invalidPromptIsAChildFailureAndDoesNotCallTheModel() throws Exception {
        List<Object> output = run(new AgentPlan(explicitParent()), 2L);
        assertThat(output).hasSize(1);
        assertThat(output.get(0).toString()).contains("only a string 'prompt'");
        assertThat(CALLS).isEmpty();
    }

    @Test
    void successiveCallsHaveIndependentChildMemory() throws Exception {
        Agent parent = new Agent();
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActSubagentTest.class.getMethod("invokeTwice", Event.class, RunnerContext.class));
        resources(parent);
        assertThat(run(new AgentPlan(parent), 1L))
                .containsExactly(List.of("child answer"), List.of("child answer"));
        assertThat(CALLS).containsExactly("child:user", "child:tool", "child:user", "child:tool");
    }

    @Test
    void structuredChildOutputIsNormalizedForModelDelegation() throws Exception {
        structured = true;
        Agent parent = new ReActAgent(model("parent"), null, null);
        resources(parent);
        assertThat(run(new AgentPlan(parent), 1L)).containsExactly("parent answer");
    }

    /** Persist values by copy, as a durable backend does, before later actions mutate memory. */
    private static class SnapshotStore extends InMemoryActionStateStore {
        SnapshotStore() {
            super(false);
        }

        @Override
        public void put(Object key, long seq, Action action, Event event, ActionState state)
                throws IOException {
            super.put(
                    key,
                    seq,
                    action,
                    event,
                    ActionStateSerde.deserialize(ActionStateSerde.serialize(state)));
        }
    }

    @Test
    void replayRestoresChildMemoryBeforeResumingItsToolLoop() throws Exception {
        InMemoryActionStateStore first = new SnapshotStore();
        assertThat(run(new AgentPlan(explicitParent()), 1L, first))
                .containsExactly(List.of("child answer"));
        assertThat(CALLS).containsExactly("child:user", "child:tool");
        Map<String, ActionState> recoveredStates = new LinkedHashMap<>();
        first.getKeyedActionStates()
                .get(1L)
                .forEach(
                        (key, state) -> {
                            if (state.getTaskEvent() instanceof InternalSubagentCallEvent) {
                                InternalSubagentCallEvent envelope =
                                        (InternalSubagentCallEvent) state.getTaskEvent();
                                // Retain the initial child request's completed actions, but drop
                                // the tool call
                                // and later actions. The restored tool response needs the earlier
                                // memory writes.
                                if (envelope.getDelegateEventType().equals(InputEvent.EVENT_TYPE)
                                        || envelope.getDelegateEventType()
                                                .equals("_chat_request_event")) {
                                    recoveredStates.put(
                                            key,
                                            ActionStateSerde.deserialize(
                                                    ActionStateSerde.serialize(state)));
                                }
                            }
                        });
        assertThat(recoveredStates).hasSize(3);
        InMemoryActionStateStore recovered = new InMemoryActionStateStore(false);
        recovered.getKeyedActionStates().put(1L, recoveredStates);
        assertThat(run(new AgentPlan(explicitParent()), 1L, recovered))
                .containsExactly(List.of("child answer"));
        assertThat(CALLS).containsExactly("child:user", "child:tool", "child:tool");
    }

    @Test
    void failedChildRemainsFailedWhenOnlyItsStateWasPersisted() throws Exception {
        InMemoryActionStateStore first = new SnapshotStore();
        List<Object> failure = run(new AgentPlan(explicitParent()), 2L, first);
        assertThat(failure.get(0).toString()).contains("only a string 'prompt'");
        Map<String, ActionState> childStates = new LinkedHashMap<>();
        first.getKeyedActionStates()
                .get(2L)
                .forEach(
                        (key, state) -> {
                            if (state.getTaskEvent() instanceof InternalSubagentCallEvent) {
                                childStates.put(
                                        key,
                                        ActionStateSerde.deserialize(
                                                ActionStateSerde.serialize(state)));
                            }
                        });
        assertThat(childStates.values())
                .anyMatch(state -> state.getSubagentFailureMessage() != null);
        InMemoryActionStateStore recovered = new InMemoryActionStateStore(false);
        recovered.getKeyedActionStates().put(2L, childStates);
        assertThat(run(new AgentPlan(explicitParent()), 2L, recovered)).isEqualTo(failure);
        assertThat(CALLS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void checkpointRestoresInFlightChildAtEachLoopStage(int stage) throws Exception {
        InMemoryActionStateStore store = new SnapshotStore();
        AgentPlan plan = new AgentPlan(explicitParent());
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                checkpointHarness(plan, store)) {
            harness.open();
            harness.processElement(new StreamRecord<>(stage == 3 ? 2L : 1L));
            // First mail starts and suspends the parent, leaving child tasks in keyed state.
            harness.getTaskMailbox().take(0).run();
            if (stage != 0) {
                while (!hasCheckpointStage(store, stage)) {
                    harness.getTaskMailbox().take(0).run();
                }
            }
            assertThat(harness.getRecordOutput()).isEmpty();
            snapshot = harness.snapshot(1L, 1L);
        }
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                checkpointHarness(plan, store)) {
            restored.initializeState(snapshot);
            restored.open();
            ((ActionExecutionOperator<Long, Object>) restored.getOperator())
                    .waitInFlightEventsFinished();
            assertThat(restored.getRecordOutput()).hasSize(1);
            Object result = restored.getRecordOutput().iterator().next().getValue();
            if (stage == 3) {
                assertThat(result.toString()).contains("only a string 'prompt'");
                assertThat(CALLS).isEmpty();
            } else {
                assertThat(result).isEqualTo(List.of("child answer"));
                assertThat(CALLS).containsExactly("child:user", "child:tool");
                assertThat(TOOL_CALLS.get()).isEqualTo(1);
            }
        }
    }

    private static boolean hasCheckpointStage(InMemoryActionStateStore store, int stage) {
        return store.getKeyedActionStates().values().stream()
                .flatMap(states -> states.values().stream())
                .anyMatch(
                        state -> {
                            if (!(state.getTaskEvent() instanceof InternalSubagentCallEvent)
                                    || !state.isCompleted()) {
                                return false;
                            }
                            InternalSubagentCallEvent event =
                                    (InternalSubagentCallEvent) state.getTaskEvent();
                            if (stage == 3) {
                                return state.getSubagentFailureMessage() != null;
                            }
                            return event.getDelegateEventType()
                                    .equals(
                                            stage == 1
                                                    ? "_chat_request_event"
                                                    : stage == 2
                                                            ? "_tool_request_event"
                                                            : "_chat_response_event");
                        });
    }

    public static void invokeNested(Event event, RunnerContext ctx) throws Exception {
        int count =
                ctx.getSensoryMemory().isExist("count")
                        ? ((Number) ctx.getSensoryMemory().get("count").getValue()).intValue()
                        : 0;
        ctx.getSensoryMemory().set("count", count + 1);
        ctx.sendEvent(new OutputEvent("before-await"));
        SubagentSetup nested = (SubagentSetup) ctx.getResource("researcher", ResourceType.AGENT);
        SubagentResult result = nested.submit(ctx, Map.of("prompt", "investigate")).await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo(List.of("child answer"));
        ctx.sendEvent(new OutputEvent(ctx.getSensoryMemory().get("count").getValue()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkpointRestoresNestedCallsWithoutRepeatingUnfinishedMemoryWrites(boolean durable)
            throws Exception {
        Agent middle = new Agent();
        middle.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActSubagentTest.class.getMethod(
                        "invokeNested", Event.class, RunnerContext.class));
        middle.addResource("researcher", ResourceType.AGENT, child());
        Agent parent = new Agent();
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActSubagentTest.class.getMethod("invoke", Event.class, RunnerContext.class));
        parent.addResource(
                "connection",
                ResourceType.CHAT_MODEL_CONNECTION,
                new ResourceDescriptor(ScriptedConnection.class.getName(), Map.of()));
        parent.addResource("researcher", ResourceType.AGENT, middle);
        AgentPlan plan = new AgentPlan(parent);
        InMemoryActionStateStore store = durable ? new SnapshotStore() : null;
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                checkpointHarness(plan, store)) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            harness.getTaskMailbox().take(0).run();
            harness.getTaskMailbox().take(0).run();
            snapshot = harness.snapshot(1L, 1L);
        }
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                checkpointHarness(plan, store)) {
            restored.initializeState(snapshot);
            restored.open();
            ((ActionExecutionOperator<Long, Object>) restored.getOperator())
                    .waitInFlightEventsFinished();
            assertThat(restored.getRecordOutput()).hasSize(1);
            assertThat(restored.getRecordOutput().iterator().next().getValue())
                    .isEqualTo(List.of("before-await", 1));
            assertThat(CALLS).containsExactly("child:user", "child:tool");
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> checkpointHarness(
            AgentPlan plan, InMemoryActionStateStore store) throws Exception {
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);
        AgentPlan reconstructed =
                MAPPER.readValue(MAPPER.writeValueAsString(plan), AgentPlan.class);
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(reconstructed, true, store),
                (KeySelector<Long, Long>) value -> value,
                TypeInformation.of(Long.class));
    }

    @Test
    void descriptorRoundTripPreservesCallableMetadataAndChildPlan() throws Exception {
        AgentPlan plan = new AgentPlan(explicitParent());
        InternalSubagentSetup setup =
                (InternalSubagentSetup)
                        plan.getResourceProviders()
                                .get(ResourceType.AGENT)
                                .get("researcher")
                                .provide(null);
        ResourceDescriptor descriptor =
                MAPPER.readValue(
                        MAPPER.writeValueAsString(setup.getDescriptor()), ResourceDescriptor.class);
        InternalSubagentSetup restored = new InternalSubagentSetup(descriptor, null);
        assertThat(restored.getDescription()).isEqualTo("Research a task");
        assertThat(MAPPER.readTree(restored.getInputSchema()).path("required").get(0).asText())
                .isEqualTo("prompt");
        assertThat(restored.getChildPlan().getActions()).containsKey("startAction");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> run(AgentPlan plan, long input) throws Exception {
        return run(plan, input, new InMemoryActionStateStore(false));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> run(AgentPlan plan, long input, InMemoryActionStateStore store)
            throws Exception {
        AgentPlan restored = MAPPER.readValue(MAPPER.writeValueAsString(plan), AgentPlan.class);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(restored, true, store),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(input));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            return ((List<StreamRecord<Object>>) harness.getRecordOutput())
                    .stream().map(StreamRecord::getValue).collect(Collectors.toList());
        }
    }
}
