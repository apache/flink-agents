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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.actions.Utils;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.agents.runtime.subgraph.SubgraphFailureEvent;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

/**
 * Internal graph calls — built-in chat and internal sub-agents alike — on the JDK&lt;21 parallel
 * execution engine.
 *
 * <p>That engine has no continuation to yield, so a caller waiting for its graph keeps its worker
 * and instead hands off the execution lock, and the graph it waits for runs and commits as a
 * dependent action outside the caller's root FIFO. Every test here would deadlock rather than fail
 * if either half of that stopped working, which is why each one asserts a result at all.
 *
 * <p>Both kinds run through the same chain, so neither gets its own dispatch, wait or completion
 * path here: what a chat call and a sub-agent call differ in is its definition, not the engine.
 */
@EnabledForJreRange(max = JRE.JAVA_20)
@Timeout(60)
public class SubgraphParallelExecutionTest {

    /**
     * The {@code num-async-threads} these graphs run with. The worker pool is sized above it: a
     * caller's wait keeps its worker while the graph it waits for draws workers from the same pool.
     */
    private static final int ASYNC_THREADS = 8;

    /** The name of {@link #leaked}, which is what a plan keys that action under. */
    private static final String GUARD_ACTION = "leaked";

    /** Name a child scope's Python-owned resource is declared under, and reported by. */
    private static final String PYTHON_CHILD_RESOURCE = "python-chat-model";

    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final Map<String, String> ACTION_THREADS = new ConcurrentHashMap<>();

    @BeforeEach
    void reset() {
        MODEL_CALLS.set(0);
        ACTION_THREADS.clear();
    }

    /** A model that answers synchronously, so no async-pool worker is needed beside the caller. */
    public static class Model extends BaseChatModelSetup {
        public Model(ResourceDescriptor descriptor, ResourceContext ctx) {
            super(descriptor, ctx);
        }

        @Override
        public void open() {}

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, Map<String, Object> args, Map<String, Object> params) {
            MODEL_CALLS.incrementAndGet();
            record("model");
            return new ChatResult(ChatMessage.assistant("answer:" + messages.get(0).getText()));
        }
    }

    private static void record(String action) {
        ACTION_THREADS.put(action, Thread.currentThread().getName());
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    public static void chatCaller(Event event, RunnerContext ctx) throws Exception {
        record("chatCaller");
        String input = String.valueOf(InputEvent.fromEvent(event).getInput());
        ChatResult response = ctx.chat("model", List.of(ChatMessage.user(input))).await();
        ctx.sendEvent(new OutputEvent(response.getText()));
    }

    public static void subagentCaller(Event event, RunnerContext ctx) throws Exception {
        record("subagentCaller");
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, promptOf(event)).await();
        ctx.sendEvent(new OutputEvent(((List<?>) result.getResult()).get(0)));
    }

    /** Issues two calls before waiting for either, so both graphs run concurrently. */
    public static void concurrentSubagentCaller(Event event, RunnerContext ctx) throws Exception {
        record("concurrentSubagentCaller");
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        var first = child.submit(ctx, "one");
        var second = child.submit(ctx, "two");
        List<String> outputs = new ArrayList<>();
        for (SubagentResult result : first.combine(second).awaitAll()) {
            outputs.add(String.valueOf(((List<?>) result.getResult()).get(0)));
        }
        ctx.sendEvent(new OutputEvent(String.join(",", outputs)));
    }

    public static void chatInsideSubagentCaller(Event event, RunnerContext ctx) throws Exception {
        record("chatInsideSubagentCaller");
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "nested").await();
        ctx.sendEvent(new OutputEvent(((List<?>) result.getResult()).get(0)));
    }

    public static void nestedSubagentCaller(Event event, RunnerContext ctx) throws Exception {
        record("nestedSubagentCaller");
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, promptOf(event)).await();
        ctx.sendEvent(new OutputEvent(((List<?>) result.getResult()).get(0)));
    }

    /** The record's own input, so each record drives a distinct graph call. */
    private static String promptOf(Event event) {
        return String.valueOf(InputEvent.fromEvent(event).getInput());
    }

    /** The child's own action: answers through a chat call of its own, one graph level down. */
    public static void childChatAction(Event event, RunnerContext ctx) throws Exception {
        record("childChatAction");
        String prompt = String.valueOf(InputEvent.fromEvent(event).getInput());
        ChatResult response = ctx.chat("model", List.of(ChatMessage.user(prompt))).await();
        ctx.sendEvent(new OutputEvent(response.getText()));
    }

    /** The child's own action: calls a grandchild, so the graph nests two levels deep. */
    public static void childSubagentAction(Event event, RunnerContext ctx) throws Exception {
        record("childSubagentAction");
        SubagentSetup grandchild =
                (SubagentSetup) ctx.getResource("grandchild", ResourceType.AGENT);
        SubagentResult result = grandchild.submit(ctx, promptOf(event)).await();
        ctx.sendEvent(new OutputEvent("child(" + ((List<?>) result.getResult()).get(0) + ")"));
    }

    public static void leafEcho(Event event, RunnerContext ctx) throws Exception {
        record("leafEcho");
        ctx.sendEvent(new OutputEvent(InputEvent.fromEvent(event).getInput()));
    }

    public static void leaked(Event event, RunnerContext ctx) {
        throw new AssertionError("Private graph event reached a user action: " + event.getType());
    }

    // ---------------------------------------------------------------------
    // Plans
    // ---------------------------------------------------------------------

    private static Agent chatModelResource(Agent agent) throws Exception {
        agent.addResource(
                "model",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(Model.class.getName()).build());
        return agent;
    }

    /** A root action that makes one built-in chat call. */
    static AgentPlan chatPlan() throws Exception {
        Agent agent = chatModelResource(new Agent());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "chatCaller", Event.class, RunnerContext.class));
        return configure(new AgentPlan(agent));
    }

    /** A root action that calls an internal sub-agent whose child answers directly. */
    static AgentPlan subagentPlan() throws Exception {
        Agent child = new Agent();
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "leafEcho", Event.class, RunnerContext.class));
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "subagentCaller", Event.class, RunnerContext.class));
        return configure(new AgentPlan(parent));
    }

    /** A root action that issues two sub-agent calls before waiting for either. */
    static AgentPlan concurrentSubagentPlan() throws Exception {
        Agent child = new Agent();
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "leafEcho", Event.class, RunnerContext.class));
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "concurrentSubagentCaller", Event.class, RunnerContext.class));
        return configure(new AgentPlan(parent));
    }

    /** A sub-agent whose child itself makes a chat call: a graph nested inside a graph. */
    static AgentPlan chatInsideSubagentPlan() throws Exception {
        Agent child = chatModelResource(new Agent());
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "childChatAction", Event.class, RunnerContext.class));
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "chatInsideSubagentCaller", Event.class, RunnerContext.class));
        return configure(new AgentPlan(parent));
    }

    /** Two levels of sub-agent call, so three graphs are in flight at once. */
    static AgentPlan nestedSubagentPlan() throws Exception {
        Agent grandchild = new Agent();
        grandchild.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "leafEcho", Event.class, RunnerContext.class));
        Agent child = new Agent();
        child.addResource("grandchild", ResourceType.AGENT, grandchild);
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "childSubagentAction", Event.class, RunnerContext.class));
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "nestedSubagentCaller", Event.class, RunnerContext.class));
        return configure(new AgentPlan(parent));
    }

    /** A one-action plan whose action is Python: what a worker on this engine cannot run. */
    private static AgentPlan pythonLeafPlan() throws Exception {
        return new AgentPlan(
                Map.of(
                        "pythonAction",
                        new Action(
                                "pythonAction",
                                new PythonFunction("child_module", "pythonAction"),
                                List.of(InputEvent.EVENT_TYPE))));
    }

    /** A Java-action plan declaring a Python-owned resource the root screen cannot see. */
    private static AgentPlan javaLeafWithPythonResource() throws Exception {
        Agent leaf = new Agent();
        leaf.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "leafEcho", Event.class, RunnerContext.class));
        AgentPlan leafPlan = new AgentPlan(leaf);
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                new HashMap<>(leafPlan.getResourceProviders());
        providers
                .computeIfAbsent(ResourceType.CHAT_MODEL, type -> new HashMap<>())
                .put(
                        PYTHON_CHILD_RESOURCE,
                        new PythonResourceProvider(
                                PYTHON_CHILD_RESOURCE,
                                ResourceType.CHAT_MODEL,
                                new PythonResourceDescriptor(
                                        "test.module", "TestChatModel", new HashMap<>())));
        return new AgentPlan(leafPlan.getActions(), providers, leafPlan.getConfig());
    }

    /**
     * A parent whose action calls {@code child}, registered as an internal sub-agent under {@code
     * scope}. The descriptor goes in as a provider rather than through {@code Agent.addResource},
     * which insists on an {@code Agent} declaration a Python action cannot be built from.
     */
    private static AgentPlan callingSubagent(String scope, AgentPlan child) throws Exception {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(
                                InternalSubagentResourceDescriptorBuilder.SETUP_CLASS)
                        .addInitialArgument(InternalSubagentResourceDescriptorBuilder.SCOPE, scope)
                        .addInitialArgument(
                                InternalSubagentResourceDescriptorBuilder.PLAN,
                                new ObjectMapper().convertValue(child, Map.class))
                        .build();
        Agent parent = new Agent();
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphParallelExecutionTest.class.getMethod(
                        "subagentCaller", Event.class, RunnerContext.class));
        AgentPlan parentPlan = new AgentPlan(parent);
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                new HashMap<>(parentPlan.getResourceProviders());
        providers
                .computeIfAbsent(ResourceType.AGENT, type -> new HashMap<>())
                .put(scope, new JavaResourceProvider(scope, ResourceType.AGENT, descriptor));
        return new AgentPlan(parentPlan.getActions(), providers, parentPlan.getConfig());
    }

    /**
     * An action that fails the test if a private graph event ever reaches it. It listens on the
     * four delegate types a call's graph exchanges and on both envelope types, so a routing mistake
     * at either level trips it.
     */
    private static Agent guardAgent() throws Exception {
        Agent guard = new Agent();
        guard.addAction(
                new String[] {
                    ChatRequestEvent.EVENT_TYPE,
                    ChatResponseEvent.EVENT_TYPE,
                    ToolRequestEvent.EVENT_TYPE,
                    ToolResponseEvent.EVENT_TYPE,
                    SubgraphEvent.EVENT_TYPE,
                    SubgraphFailureEvent.EVENT_TYPE
                },
                // An action's name is its method's, so this is what the plan keys it under.
                SubgraphParallelExecutionTest.class.getMethod(
                        GUARD_ACTION, Event.class, RunnerContext.class));
        return guard;
    }

    /** Wires the async threads these graphs run with and {@link #guardAgent()}. */
    private static AgentPlan configure(AgentPlan plan) throws Exception {
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, ASYNC_THREADS);
        // The guard lives in the root plan only: a call's private events must never reach it.
        Map<String, Action> actions = new java.util.LinkedHashMap<>(plan.getActions());
        actions.put(GUARD_ACTION, new AgentPlan(guardAgent()).getActions().get(GUARD_ACTION));
        return new AgentPlan(actions, plan.getResourceProviders(), plan.getConfig());
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    private static List<AgentPlan> allPlans() throws Exception {
        return List.of(
                chatPlan(),
                subagentPlan(),
                concurrentSubagentPlan(),
                chatInsideSubagentPlan(),
                nestedSubagentPlan());
    }

    /** Guards every test below: without it a green run could mean the engine never engaged. */
    @Test
    void thesePlansRunOnTheParallelEngine() throws Exception {
        assertThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        for (AgentPlan plan : allPlans()) {
            assertThat(
                            ActionExecutionOperator.shouldEnableParallelExecutionWithoutCoroutine(
                                    plan, false))
                    .isTrue();
        }
    }

    /**
     * The other half of that guarantee: a green run below could also mean the leak guard was never
     * installed, or was installed but cannot fail.
     */
    @Test
    void guardIsInstalledAndFailsWhenTriggered() throws Exception {
        Action guard = new AgentPlan(guardAgent()).getActions().get(GUARD_ACTION);
        assertThat(guard).isNotNull();
        assertThat(guard.getTriggerConditions())
                .contains(
                        ChatRequestEvent.EVENT_TYPE,
                        ChatResponseEvent.EVENT_TYPE,
                        ToolRequestEvent.EVENT_TYPE,
                        ToolResponseEvent.EVENT_TYPE,
                        SubgraphEvent.EVENT_TYPE,
                        SubgraphFailureEvent.EVENT_TYPE);
        for (AgentPlan plan : allPlans()) {
            assertThat(plan.getActions()).containsKey(guard.getName());
        }
        assertThatThrownBy(() -> leaked(new Event(SubgraphEvent.EVENT_TYPE), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Private graph event reached a user action");
    }

    @Test
    void chatCallCompletes() throws Exception {
        assertThat(run(chatPlan(), "hello")).containsExactly("answer:hello");
        assertThat(MODEL_CALLS.get()).isEqualTo(1);
        // The model ran off the caller's worker: the caller released the lock while it waited.
        assertThat(ACTION_THREADS.get("model")).isNotEqualTo(ACTION_THREADS.get("chatCaller"));
    }

    @Test
    void subagentCallCompletes() throws Exception {
        assertThat(run(subagentPlan(), "hello")).containsExactly("hello");
        assertThat(ACTION_THREADS.get("leafEcho"))
                .isNotEqualTo(ACTION_THREADS.get("subagentCaller"));
    }

    @Test
    void concurrentSubagentCallsBothComplete() throws Exception {
        assertThat(run(concurrentSubagentPlan(), "hello")).containsExactly("one,two");
    }

    @Test
    void chatInsideSubagentCompletes() throws Exception {
        assertThat(run(chatInsideSubagentPlan(), "hello")).containsExactly("answer:nested");
        assertThat(MODEL_CALLS.get()).isEqualTo(1);
    }

    @Test
    void nestedSubagentCallsComplete() throws Exception {
        assertThat(run(nestedSubagentPlan(), "hello")).containsExactly("child(hello)");
    }

    /**
     * A snapshot taken on this engine drains in-flight work first, so the restored operator resumes
     * from settled durable state: it must reopen cleanly, dispatch a further record, and keep graph
     * events private across the restore.
     */
    @Test
    void restoresAndContinuesAfterCheckpoint() throws Exception {
        for (AgentPlan plan : List.of(chatPlan(), subagentPlan(), nestedSubagentPlan())) {
            MODEL_CALLS.set(0);
            ACTION_THREADS.clear();
            org.apache.flink.runtime.checkpoint.OperatorSubtaskState checkpoint;
            try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> original =
                    harness(plan)) {
                original.open();
                original.processElement(new StreamRecord<>("first"));
                ((ActionExecutionOperator<?, ?>) original.getOperator())
                        .waitInFlightEventsFinished();
                assertThat(original.getRecordOutput()).hasSize(1);
                checkpoint = original.snapshot(1, 1);
            }
            try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> restored =
                    harness(plan)) {
                restored.initializeState(checkpoint);
                restored.open();
                restored.processElement(new StreamRecord<>("second"));
                ((ActionExecutionOperator<?, ?>) restored.getOperator())
                        .waitInFlightEventsFinished();
                assertThat(values(restored)).hasSize(1);
            }
        }
    }

    /**
     * A caller's wait keeps its worker on this engine, so the pool has to hold the caller and the
     * graph it waits for at once. {@code num-async-threads} alone would not give that: at one
     * thread the caller blocks the only worker able to run the graph, and a pool that small can
     * neither grow nor reject, so the job hangs until Flink's checkpoint timeout fails it without
     * naming the cause. The worker ceiling lifts the pool above this configuration, and the
     * smallest one still completes a call. The class-level timeout is what makes "does not hang" an
     * assertion here.
     */
    @Test
    void singleAsyncThreadStillCompletesAGraphCall() throws Exception {
        AgentPlan plan = chatPlan();
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);

        assertThat(run(plan, "hello")).containsExactly("answer:hello");
    }

    /**
     * The same headroom measured in depth rather than fan-out: every link of this chain but the
     * leaf blocks a worker, so it needs more of them than the two threads configured here.
     */
    @Test
    void nestedChainDeeperThanAsyncThreadsCompletes() throws Exception {
        AgentPlan plan = nestedSubagentPlan();
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        assertThat(run(plan, "hello")).containsExactly("child(hello)");
    }

    /**
     * A worker cannot execute Python, and the engine choice reads only the root plan, which is pure
     * Java here. Left to dispatch, the mismatch would surface once a record reaches the call, after
     * its predecessors had emitted downstream and without naming the offending scope.
     */
    @Test
    void pythonChildActionIsRejectedWhenTheOperatorOpens() throws Exception {
        AgentPlan oneLevel = configure(callingSubagent("child", pythonLeafPlan()));
        AgentPlan twoLevels =
                configure(
                        callingSubagent("child", callingSubagent("grandchild", pythonLeafPlan())));
        for (AgentPlan plan : List.of(oneLevel, twoLevels)) {
            assertThat(
                            ActionExecutionOperator.shouldEnableParallelExecutionWithoutCoroutine(
                                    plan, false))
                    .as("the root plan alone gives no reason to decline the engine")
                    .isTrue();
        }

        assertRejectedOnOpen(oneLevel, "action pythonAction of scope child");
        assertRejectedOnOpen(twoLevels, "action pythonAction of scope grandchild");
    }

    /**
     * The root screen cannot see a child scope's declaration, and declining the engine is not an
     * answer here: the call this child exists for needs the engine on this JDK.
     */
    @Test
    void pythonChildResourceIsRejectedUnlessFlinkCanCallItAsynchronously() throws Exception {
        AgentPlan plan = configure(callingSubagent("child", javaLeafWithPythonResource()));
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(Utils::supportAsync).thenReturn(true);
            assertThatCode(() -> ActionExecutionOperator.checkChildPlansRunOnParallelEngine(plan))
                    .doesNotThrowAnyException();

            utils.when(Utils::supportAsync).thenReturn(false);
            assertThat(
                            ActionExecutionOperator.shouldEnableParallelExecutionWithoutCoroutine(
                                    plan, false))
                    .as("the root plan alone gives no reason to decline the engine")
                    .isTrue();
            assertThatThrownBy(
                            () -> ActionExecutionOperator.checkChildPlansRunOnParallelEngine(plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scope child declares [" + PYTHON_CHILD_RESOURCE + "]");
            assertRejectedOnOpen(plan, "scope child declares [" + PYTHON_CHILD_RESOURCE + "]");
        }
    }

    /** The same declaration one level down, so a depth-limited screen would miss it. */
    @Test
    void pythonGrandchildResourceIsRejectedWhenTheOperatorOpens() throws Exception {
        AgentPlan plan =
                configure(
                        callingSubagent(
                                "child",
                                callingSubagent("grandchild", javaLeafWithPythonResource())));
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(Utils::supportAsync).thenReturn(false);
            assertRejectedOnOpen(plan, "scope grandchild declares [" + PYTHON_CHILD_RESOURCE + "]");
        }
    }

    private static void assertRejectedOnOpen(AgentPlan plan, String message) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                harness(plan)) {
            assertThatThrownBy(harness::open)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(message);
        }
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    private static KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness(
            AgentPlan plan) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true),
                (KeySelector<String, String>) value -> value,
                TypeInformation.of(String.class));
    }

    private static List<Object> run(AgentPlan plan, String input) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                harness(plan)) {
            harness.open();
            harness.processElement(new StreamRecord<>(input));
            ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
            return values(harness);
        }
    }

    private static List<Object> values(
            KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness) {
        List<Object> values = new ArrayList<>();
        for (Object record : harness.getRecordOutput()) {
            values.add(((StreamRecord<?>) record).getValue());
        }
        return values;
    }
}
