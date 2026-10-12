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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * End-to-end scenarios for parallel action execution, covering fan-out, broadcast, multi-key,
 * context switch, same-key input order, capacity, failure propagation and backpressure. The suite
 * runs on both execution engines; only the backpressure scenario is specific to the JDK&lt;21
 * blocking-inline engine.
 *
 * <p>Timing observations are collected in the in-JVM {@link TimingLog} (valid because every
 * scenario runs on a same-JVM MiniCluster). Its append order is worker completion order, so
 * order-sensitive assertions always go against the collected job outputs, which carry one {@code
 * recordId:action} marker per terminal {@link OutputEvent}.
 */
public class ParallelExecutionE2ETest {

    private static final int FANOUT_SLEEP_MS = 1500;
    private static final int FANOUT_HANDLERS = 3;

    private static final int BROADCAST_LONG_SLEEP_MS = 1200;
    private static final int BROADCAST_SHORT_SLEEP_MS = 100;
    private static final int BROADCAST_HANDLERS = 3;

    private static final int MULTI_KEY_SLEEP_MS = 1500;
    private static final int MULTI_KEY_RECORDS = 3;
    private static final CountDownLatch MULTI_KEY_ALL_STARTED =
            new CountDownLatch(MULTI_KEY_RECORDS);

    private static final String CTX_OWNER_CONFIG = "context-owner";
    private static final String CTX_MEMORY_OWNER = "context-owner";
    private static final int CTX_SLEEP_MS = 1500;
    private static final int CTX_HANDLERS = 3;
    /** Independent oracle for the action-specific context configuration. */
    private static final Map<String, String> CTX_EXPECTED_OWNER_BY_HANDLER =
            Map.of(
                    "handleAlpha", "ALPHA",
                    "handleBeta", "BETA",
                    "handleGamma", "GAMMA");

    private static final int SAME_KEY = 7;
    private static final int SAME_KEY_INPUTS = 2;
    private static final int SAME_KEY_ACTIONS = 2;
    private static final int SAME_KEY_ASYNC_THREADS = 2;
    private static final int SAME_KEY_LONG_SLEEP_MS = 1000;
    private static final int SAME_KEY_SHORT_SLEEP_MS = 100;

    private static final int CAPACITY_ASYNC_THREADS = 2;
    private static final int CAPACITY_ACTIONS = CAPACITY_ASYNC_THREADS * 2 + 2;
    private static final int CAPACITY_SLEEP_MS = 350;

    private static final String FAILURE_MARKER = "scenario-async-failure-marker";

    private static final int BACKPRESSURE_ASYNC_THREADS = 2;
    private static final int BACKPRESSURE_MAX_IN_FLIGHT = 1;
    private static final int BACKPRESSURE_SLEEP_MS = 700;

    @BeforeEach
    void clearRegistries() {
        TimingLog.clear();
        ContextObservations.clear();
    }

    /** One timed execution on an async worker. */
    static final class Span {
        final String action;
        final String key;
        final String recordId;
        final int slot;
        final long startMs;
        final long endMs;
        final String thread;

        Span(
                String action,
                String key,
                String recordId,
                int slot,
                long startMs,
                long endMs,
                String thread) {
            this.action = action;
            this.key = key;
            this.recordId = recordId;
            this.slot = slot;
            this.startMs = startMs;
            this.endMs = endMs;
            this.thread = thread;
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "%s:%s#%d[%d..%d]@%s",
                    recordId,
                    action,
                    slot,
                    startMs,
                    endMs,
                    thread);
        }
    }

    /** In-JVM timing registry; append order is worker completion order, not sink output order. */
    static final class TimingLog {
        private static final List<Span> SPANS = Collections.synchronizedList(new ArrayList<>());

        static void clear() {
            SPANS.clear();
        }

        /** Times the sleep, records the span, and returns the {@code recordId:action} marker. */
        static String run(String action, Object key, Object recordId, int slot, int sleepMs)
                throws Exception {
            long start = System.currentTimeMillis();
            Thread.sleep(sleepMs);
            long end = System.currentTimeMillis();
            SPANS.add(
                    new Span(
                            action,
                            String.valueOf(key),
                            String.valueOf(recordId),
                            slot,
                            start,
                            end,
                            Thread.currentThread().getName()));
            return recordId + ":" + action;
        }

        static List<Span> spans() {
            synchronized (SPANS) {
                return new ArrayList<>(SPANS);
            }
        }
    }

    private static List<Span> spansOfSlot(int slot) {
        List<Span> result = new ArrayList<>();
        for (Span span : TimingLog.spans()) {
            if (span.slot == slot) {
                result.add(span);
            }
        }
        return result;
    }

    private static List<Span> spansOfRecord(String recordId) {
        List<Span> result = new ArrayList<>();
        for (Span span : TimingLog.spans()) {
            if (recordId.equals(span.recordId)) {
                result.add(span);
            }
        }
        return result;
    }

    private static Span spanOf(String action) {
        return TimingLog.spans().stream()
                .filter(span -> action.equals(span.action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing span for action " + action));
    }

    private static String describe(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingLong(span -> span.startMs));
        return sorted.toString();
    }

    /** Every pair of spans must overlap in time. */
    private static void assertAllPairsOverlap(String description, List<Span> spans) {
        assertThat(spans.size())
                .as("%s needs at least two spans to compare", description)
                .isGreaterThanOrEqualTo(2);
        for (int i = 0; i < spans.size(); i++) {
            for (int j = i + 1; j < spans.size(); j++) {
                Span a = spans.get(i);
                Span b = spans.get(j);
                assertThat(a.startMs < b.endMs && b.startMs < a.endMs)
                        .as(
                                "%s: %s and %s must overlap; spans=%s",
                                description, a, b, describe(spans))
                        .isTrue();
            }
        }
    }

    /** Sum of span durations over the aggregate wall time. */
    private static void assertSpeedup(String description, List<Span> spans, double minRatio) {
        long minStart = spans.stream().mapToLong(span -> span.startMs).min().orElseThrow();
        long maxEnd = spans.stream().mapToLong(span -> span.endMs).max().orElseThrow();
        long sumDurations = spans.stream().mapToLong(span -> span.endMs - span.startMs).sum();
        long wall = Math.max(maxEnd - minStart, 1L);
        assertThat((double) sumDurations / wall)
                .as("%s: speedup over wall time; spans=%s", description, describe(spans))
                .isGreaterThanOrEqualTo(minRatio);
    }

    private static int distinctThreads(List<Span> spans) {
        return (int) spans.stream().map(span -> span.thread).distinct().count();
    }

    /** Uniform request POJO for all scenarios; {@code recordId} and {@code key} vary per test. */
    public static class TestRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        public final int recordId;
        public final int key;

        public TestRequest(int recordId, int key) {
            this.recordId = recordId;
            this.key = key;
        }
    }

    public static class TestRequestKeySelector implements KeySelector<TestRequest, Integer> {
        @Override
        public Integer getKey(TestRequest request) {
            return request.key;
        }
    }

    /** Runs a single-parallelism agent job and returns the collected outputs in sink order. */
    private static List<String> runJob(
            Agent agent, Consumer<AgentsExecutionEnvironment> configure, TestRequest... inputs)
            throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // Keep one Flink subtask so observed overlap must come from the JDK<21 action workers.
        env.setParallelism(1);
        DataStream<TestRequest> inputStream = env.fromElements(inputs);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        configure.accept(agentsEnv);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new TestRequestKeySelector())
                        .apply(agent)
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> outputs = new ArrayList<>();
        while (results.hasNext()) {
            outputs.add(results.next().toString());
        }
        results.close();
        return outputs;
    }

    /** Serializable body used by {@link #durableTiming} hooks. */
    @FunctionalInterface
    public interface ThrowingRunnable extends Serializable {
        void run() throws Exception;
    }

    /** One timed {@code durableExecuteAsync} call; returns the {@code recordId:action} marker. */
    private static String durableTiming(
            RunnerContext ctx,
            String id,
            String action,
            Object key,
            Object recordId,
            int slot,
            int sleepMs)
            throws Exception {
        return durableTiming(ctx, id, action, key, recordId, slot, sleepMs, null);
    }

    /**
     * Variant with a {@code beforeTiming} hook running inside the worker callable before the timed
     * window opens (e.g. a start-alignment barrier), so the hook never inflates the span.
     */
    private static String durableTiming(
            RunnerContext ctx,
            String id,
            String action,
            Object key,
            Object recordId,
            int slot,
            int sleepMs,
            ThrowingRunnable beforeTiming)
            throws Exception {
        return ctx.durableExecuteAsync(
                        new DurableCallable<String>() {
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
                                if (beforeTiming != null) {
                                    beforeTiming.run();
                                }
                                return TimingLog.run(action, key, recordId, slot, sleepMs);
                            }
                        })
                .await();
    }

    /** One {@code durableExecuteAsync} call whose worker body always throws. */
    private static void durableFailure(RunnerContext ctx, String id, String marker)
            throws Exception {
        ctx.durableExecuteAsync(
                        new DurableCallable<String>() {
                            @Override
                            public String getId() {
                                return id;
                            }

                            @Override
                            public Class<String> getResultClass() {
                                return String.class;
                            }

                            @Override
                            public String call() {
                                throw new IllegalStateException(marker);
                            }
                        })
                .await();
    }

    private static Method method(Class<?> agentClass, String name) {
        try {
            return agentClass.getMethod(name, Event.class, RunnerContext.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing action method: " + name, e);
        }
    }

    @Test
    @Timeout(30)
    public void fanoutRunsSiblingActionsInParallel() throws Exception {
        List<String> outputs =
                runJob(
                        new FanoutAgent(),
                        agentsEnv ->
                                agentsEnv
                                        .getConfig()
                                        .set(
                                                AgentExecutionOptions.NUM_ASYNC_THREADS,
                                                FANOUT_HANDLERS),
                        new TestRequest(1, 1));

        assertThat(outputs).containsExactlyInAnyOrder("1:handleA", "1:handleB", "1:handleC");

        List<Span> spans = TimingLog.spans();
        assertThat(spans).hasSize(FANOUT_HANDLERS);
        assertThat(spans)
                .extracting(span -> span.action)
                .containsExactlyInAnyOrder("handleA", "handleB", "handleC");
        assertAllPairsOverlap("fan-out siblings", spans);
        assertThat(distinctThreads(spans)).isGreaterThanOrEqualTo(2);
        assertSpeedup("fan-out siblings", spans, 2.0);
    }

    /** Agent: each handler consumes the input directly and does one durableExecuteAsync. */
    public static class FanoutAgent extends Agent {

        @Action(InputEvent.EVENT_TYPE)
        public static void handleA(Event event, RunnerContext ctx) throws Exception {
            invokeAndEmit(event, ctx, "handleA");
        }

        @Action(InputEvent.EVENT_TYPE)
        public static void handleB(Event event, RunnerContext ctx) throws Exception {
            invokeAndEmit(event, ctx, "handleB");
        }

        @Action(InputEvent.EVENT_TYPE)
        public static void handleC(Event event, RunnerContext ctx) throws Exception {
            invokeAndEmit(event, ctx, "handleC");
        }

        private static void invokeAndEmit(Event event, RunnerContext ctx, String action)
                throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            String marker =
                    durableTiming(
                            ctx,
                            "fanout-" + action + "-" + request.recordId,
                            action,
                            request.key,
                            request.recordId,
                            1,
                            FANOUT_SLEEP_MS);
            ctx.sendEvent(new OutputEvent(marker));
        }
    }

    @Test
    @Timeout(30)
    public void broadcastRunsAllListenersInParallel() throws Exception {
        List<String> outputs =
                runJob(
                        new BroadcastAgent(),
                        agentsEnv ->
                                agentsEnv
                                        .getConfig()
                                        .set(
                                                AgentExecutionOptions.NUM_ASYNC_THREADS,
                                                BROADCAST_HANDLERS),
                        new TestRequest(1, 1));

        assertThat(outputs)
                .containsExactlyInAnyOrder("1:handleAlpha", "1:handleBeta", "1:handleGamma");

        List<Span> spans = TimingLog.spans();
        assertThat(spans).hasSize(BROADCAST_HANDLERS);
        assertAllPairsOverlap("broadcast listeners", spans);
        assertThat(distinctThreads(spans)).isGreaterThanOrEqualTo(2);

        // The short-sleep listeners must finish before the long-sleep one.
        Span alpha = spanOf("handleAlpha");
        Span beta = spanOf("handleBeta");
        Span gamma = spanOf("handleGamma");
        assertThat(beta.endMs).isLessThan(alpha.endMs);
        assertThat(gamma.endMs).isLessThan(alpha.endMs);
    }

    /** Broadcast event consumed by every listener in this scenario. */
    public static class FanBroadcastEvent extends Event {
        public static final String EVENT_TYPE = "ParallelExecutionE2ETest.FanBroadcastEvent";

        public final int inputId;

        public FanBroadcastEvent(int inputId) {
            super(EVENT_TYPE);
            this.inputId = inputId;
        }
    }

    /** Agent: prime sends one FanBroadcastEvent; three listeners share the same EVENT_TYPE. */
    public static class BroadcastAgent extends Agent {

        public BroadcastAgent() {
            addAction(new String[] {InputEvent.EVENT_TYPE}, method(BroadcastAgent.class, "prime"));
            addAction(
                    new String[] {FanBroadcastEvent.EVENT_TYPE},
                    method(BroadcastAgent.class, "handleAlpha"));
            addAction(
                    new String[] {FanBroadcastEvent.EVENT_TYPE},
                    method(BroadcastAgent.class, "handleBeta"));
            addAction(
                    new String[] {FanBroadcastEvent.EVENT_TYPE},
                    method(BroadcastAgent.class, "handleGamma"));
        }

        public static void prime(Event event, RunnerContext ctx) throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new FanBroadcastEvent(request.recordId));
        }

        public static void handleAlpha(Event event, RunnerContext ctx) throws Exception {
            invokeAndEmit(event, ctx, "handleAlpha", BROADCAST_LONG_SLEEP_MS);
        }

        public static void handleBeta(Event event, RunnerContext ctx) throws Exception {
            invokeAndEmit(event, ctx, "handleBeta", BROADCAST_SHORT_SLEEP_MS);
        }

        public static void handleGamma(Event event, RunnerContext ctx) throws Exception {
            invokeAndEmit(event, ctx, "handleGamma", BROADCAST_SHORT_SLEEP_MS);
        }

        private static void invokeAndEmit(
                Event event, RunnerContext ctx, String action, int sleepMs) throws Exception {
            FanBroadcastEvent in = (FanBroadcastEvent) event;
            String marker =
                    durableTiming(
                            ctx,
                            "broadcast-" + action + "-" + in.inputId,
                            action,
                            in.inputId,
                            in.inputId,
                            1,
                            sleepMs);
            ctx.sendEvent(new OutputEvent(marker));
        }
    }

    @Test
    @Timeout(30)
    public void multiKeyInputsRunInParallel() throws Exception {
        List<String> outputs =
                runJob(
                        new MultiKeyAgent(),
                        agentsEnv ->
                                agentsEnv
                                        .getConfig()
                                        .set(
                                                AgentExecutionOptions.NUM_ASYNC_THREADS,
                                                MULTI_KEY_RECORDS),
                        new TestRequest(1, 1),
                        new TestRequest(2, 2),
                        new TestRequest(3, 3));

        assertThat(outputs).containsExactlyInAnyOrder("1:run", "2:run", "3:run");

        List<Span> spans = TimingLog.spans();
        assertThat(spans).hasSize(MULTI_KEY_RECORDS);
        assertThat(spans)
                .extracting(span -> span.recordId)
                .containsExactlyInAnyOrder("1", "2", "3");
        assertAllPairsOverlap("multi-key inputs", spans);
        assertThat(distinctThreads(spans)).isGreaterThanOrEqualTo(2);
        assertSpeedup("multi-key inputs", spans, 2.0);
    }

    /** Agent with a single action issuing exactly one durableExecuteAsync call. */
    public static class MultiKeyAgent extends Agent {

        @Action(InputEvent.EVENT_TYPE)
        public static void run(Event event, RunnerContext ctx) throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            String marker =
                    durableTiming(
                            ctx,
                            "multikey-run-" + request.recordId,
                            "run",
                            request.key,
                            request.recordId,
                            1,
                            MULTI_KEY_SLEEP_MS,
                            () -> {
                                // Start-alignment barrier; runs before the timed window opens.
                                MULTI_KEY_ALL_STARTED.countDown();
                                if (!MULTI_KEY_ALL_STARTED.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "Timed out waiting for all multi-key actions to start");
                                }
                            });
            ctx.sendEvent(new OutputEvent(marker));
        }
    }

    @Test
    @Timeout(20)
    public void parallelSiblingsKeepActionScopedContext() throws Exception {
        List<String> outputs =
                runJob(
                        new ContextSwitchAgent(),
                        agentsEnv ->
                                agentsEnv
                                        .getConfig()
                                        .set(AgentExecutionOptions.NUM_ASYNC_THREADS, CTX_HANDLERS),
                        new TestRequest(1, 1));

        assertThat(outputs)
                .containsExactlyInAnyOrder("1:handleAlpha", "1:handleBeta", "1:handleGamma");

        List<ContextObservation> observations = ContextObservations.snapshot();
        assertThat(observations).hasSize(CTX_HANDLERS);
        List<String> handlers = new ArrayList<>();
        for (ContextObservation observation : observations) {
            handlers.add(observation.handler);
            String expectedOwner = CTX_EXPECTED_OWNER_BY_HANDLER.get(observation.handler);
            assertThat(expectedOwner)
                    .as("missing expected owner for handler %s", observation.handler)
                    .isNotNull();
            assertThat(observation.beforeConfig).isEqualTo(expectedOwner);
            assertThat(observation.beforeMemory).isEqualTo(expectedOwner);
            assertThat(observation.after1Config).isEqualTo(expectedOwner);
            assertThat(observation.after1Memory).isEqualTo(expectedOwner);
            assertThat(observation.after2Config).isEqualTo(expectedOwner);
            assertThat(observation.after2Memory).isEqualTo(expectedOwner);
        }
        assertThat(handlers).containsExactlyInAnyOrder("handleAlpha", "handleBeta", "handleGamma");

        List<Span> firstCalls = spansOfSlot(1);
        List<Span> secondCalls = spansOfSlot(2);
        assertThat(firstCalls).hasSize(CTX_HANDLERS);
        assertThat(secondCalls).hasSize(CTX_HANDLERS);
        assertAllPairsOverlap("context-switch slot 1", firstCalls);
        assertAllPairsOverlap("context-switch slot 2", secondCalls);
        assertThat(distinctThreads(firstCalls)).isEqualTo(CTX_HANDLERS);
        assertThat(distinctThreads(secondCalls)).isEqualTo(CTX_HANDLERS);
        assertSpeedup("context-switch slot 1", firstCalls, 2.0);
        assertSpeedup("context-switch slot 2", secondCalls, 2.0);
    }

    /** Context values observed by one handler around its two yielding calls. */
    static final class ContextObservation {
        final String handler;
        final String beforeConfig;
        final String beforeMemory;
        final String after1Config;
        final String after1Memory;
        final String after2Config;
        final String after2Memory;

        ContextObservation(
                String handler,
                String beforeConfig,
                String beforeMemory,
                String after1Config,
                String after1Memory,
                String after2Config,
                String after2Memory) {
            this.handler = handler;
            this.beforeConfig = beforeConfig;
            this.beforeMemory = beforeMemory;
            this.after1Config = after1Config;
            this.after1Memory = after1Memory;
            this.after2Config = after2Config;
            this.after2Memory = after2Memory;
        }
    }

    /** In-JVM registry of context observations, cleared before each test. */
    static final class ContextObservations {
        private static final List<ContextObservation> OBSERVATIONS =
                Collections.synchronizedList(new ArrayList<>());

        static void clear() {
            OBSERVATIONS.clear();
        }

        static void record(ContextObservation observation) {
            OBSERVATIONS.add(observation);
        }

        static List<ContextObservation> snapshot() {
            synchronized (OBSERVATIONS) {
                return new ArrayList<>(OBSERVATIONS);
            }
        }
    }

    public static class ContextSwitchEvent extends Event {
        public static final String EVENT_TYPE = "ParallelExecutionE2ETest.ContextSwitchEvent";

        public final int inputId;

        public ContextSwitchEvent(int inputId) {
            super(EVENT_TYPE);
            this.inputId = inputId;
        }
    }

    /** Agent whose three same-key siblings each own a distinct action-scoped config value. */
    public static class ContextSwitchAgent extends Agent {

        public ContextSwitchAgent() {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    method(ContextSwitchAgent.class, "prime"));
            addAction(
                    new String[] {ContextSwitchEvent.EVENT_TYPE},
                    method(ContextSwitchAgent.class, "handleAlpha"),
                    ownerConfig("ALPHA"));
            addAction(
                    new String[] {ContextSwitchEvent.EVENT_TYPE},
                    method(ContextSwitchAgent.class, "handleBeta"),
                    ownerConfig("BETA"));
            addAction(
                    new String[] {ContextSwitchEvent.EVENT_TYPE},
                    method(ContextSwitchAgent.class, "handleGamma"),
                    ownerConfig("GAMMA"));
        }

        public static void prime(Event event, RunnerContext ctx) throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(new ContextSwitchEvent(request.recordId));
        }

        public static void handleAlpha(Event event, RunnerContext ctx) throws Exception {
            handle(event, ctx, "handleAlpha");
        }

        public static void handleBeta(Event event, RunnerContext ctx) throws Exception {
            handle(event, ctx, "handleBeta");
        }

        public static void handleGamma(Event event, RunnerContext ctx) throws Exception {
            handle(event, ctx, "handleGamma");
        }

        private static void handle(Event event, RunnerContext ctx, String action) throws Exception {
            ContextSwitchEvent input = (ContextSwitchEvent) event;
            String expectedOwner = CTX_EXPECTED_OWNER_BY_HANDLER.get(action);
            if (expectedOwner == null) {
                throw new IllegalStateException("Missing expected owner for handler: " + action);
            }
            ctx.getShortTermMemory().set(CTX_MEMORY_OWNER, expectedOwner);

            String[] before = snapshot(ctx);
            durableTiming(
                    ctx,
                    "context-switch-" + expectedOwner + "-" + input.inputId + "-1",
                    action,
                    input.inputId,
                    input.inputId,
                    1,
                    CTX_SLEEP_MS);
            String[] afterFirst = snapshot(ctx);
            durableTiming(
                    ctx,
                    "context-switch-" + expectedOwner + "-" + input.inputId + "-2",
                    action,
                    input.inputId,
                    input.inputId,
                    2,
                    CTX_SLEEP_MS);
            String[] afterSecond = snapshot(ctx);

            assertSnapshot(action, "before", expectedOwner, before);
            assertSnapshot(action, "after1", expectedOwner, afterFirst);
            assertSnapshot(action, "after2", expectedOwner, afterSecond);

            ContextObservations.record(
                    new ContextObservation(
                            action,
                            before[0],
                            before[1],
                            afterFirst[0],
                            afterFirst[1],
                            afterSecond[0],
                            afterSecond[1]));
            // Emitted only after all yielding calls have returned.
            ctx.sendEvent(new OutputEvent(input.inputId + ":" + action));
        }

        /** Returns {@code [configOwner, memoryOwner]} as observed through the runner context. */
        private static String[] snapshot(RunnerContext ctx) throws Exception {
            Object configOwner = ctx.getActionConfigValue(CTX_OWNER_CONFIG);
            MemoryObject memoryOwner = ctx.getShortTermMemory().get(CTX_MEMORY_OWNER);
            Object memoryValue = memoryOwner == null ? null : memoryOwner.getValue();
            return new String[] {
                configOwner == null ? null : configOwner.toString(),
                memoryValue == null ? null : memoryValue.toString()
            };
        }

        private static void assertSnapshot(
                String action, String phase, String expected, String[] snapshot) {
            if (!expected.equals(snapshot[0]) || !expected.equals(snapshot[1])) {
                throw new IllegalStateException(
                        "Context mismatch: action="
                                + action
                                + ", phase="
                                + phase
                                + ", expected="
                                + expected
                                + ", configOwner="
                                + snapshot[0]
                                + ", memoryOwner="
                                + snapshot[1]);
            }
        }

        private static Map<String, Object> ownerConfig(String owner) {
            Map<String, Object> config = new HashMap<>();
            config.put(CTX_OWNER_CONFIG, owner);
            return config;
        }
    }

    @Test
    @Timeout(25)
    public void sameKeyInputsCommitInOrder() throws Exception {
        List<String> outputs =
                runJob(
                        new SameKeyInputOrderAgent(),
                        agentsEnv ->
                                agentsEnv
                                        .getConfig()
                                        .set(
                                                AgentExecutionOptions.NUM_ASYNC_THREADS,
                                                SAME_KEY_ASYNC_THREADS),
                        new TestRequest(1, SAME_KEY),
                        new TestRequest(2, SAME_KEY));

        // Both markers of input 1 must be emitted before both markers of input 2; the order of
        // siblings within one input is a scheduling detail that varies across Flink versions.
        assertThat(outputs).hasSize(SAME_KEY_INPUTS * SAME_KEY_ACTIONS);
        assertThat(outputs.subList(0, SAME_KEY_ACTIONS))
                .containsExactlyInAnyOrder("1:actionA", "1:actionB");
        assertThat(outputs.subList(SAME_KEY_ACTIONS, SAME_KEY_INPUTS * SAME_KEY_ACTIONS))
                .containsExactlyInAnyOrder("2:actionA", "2:actionB");

        List<Span> spans = TimingLog.spans();
        assertThat(spans).hasSize(SAME_KEY_INPUTS * SAME_KEY_ACTIONS);

        List<Span> firstInput = spansOfRecord("1");
        List<Span> secondInput = spansOfRecord("2");
        long firstInputEnd = firstInput.stream().mapToLong(span -> span.endMs).max().orElseThrow();
        long secondInputStart =
                secondInput.stream().mapToLong(span -> span.startMs).min().orElseThrow();
        assertThat(secondInputStart)
                .as("The second same-key input must wait for the first input to commit")
                .isGreaterThanOrEqualTo(firstInputEnd);
    }

    /** Agent with two sibling actions per input, one long and one short. */
    public static class SameKeyInputOrderAgent extends Agent {

        public SameKeyInputOrderAgent() {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    method(SameKeyInputOrderAgent.class, "actionA"));
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    method(SameKeyInputOrderAgent.class, "actionB"));
        }

        public static void actionA(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "actionA", SAME_KEY_LONG_SLEEP_MS);
        }

        public static void actionB(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "actionB", SAME_KEY_SHORT_SLEEP_MS);
        }

        private static void executeAndEmit(
                Event event, RunnerContext ctx, String action, int sleepMs) throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            String marker =
                    durableTiming(
                            ctx,
                            "same-key-" + request.recordId + "-" + action,
                            action,
                            request.key,
                            request.recordId,
                            1,
                            sleepMs);
            ctx.sendEvent(new OutputEvent(marker));
        }
    }

    @Test
    @Timeout(25)
    public void tasksBeyondWorkerCountCompleteExactlyOnce() throws Exception {
        List<String> outputs =
                runJob(
                        new CapacityAgent(),
                        agentsEnv ->
                                agentsEnv
                                        .getConfig()
                                        .set(
                                                AgentExecutionOptions.NUM_ASYNC_THREADS,
                                                CAPACITY_ASYNC_THREADS),
                        new TestRequest(1, 1));

        assertThat(outputs)
                .containsExactlyInAnyOrder(
                        "1:action1",
                        "1:action2",
                        "1:action3",
                        "1:action4",
                        "1:action5",
                        "1:action6");

        List<Span> spans = TimingLog.spans();
        assertThat(spans).hasSize(CAPACITY_ACTIONS);
        assertThat(spans)
                .extracting(span -> span.action)
                .containsExactlyInAnyOrder(
                        "action1", "action2", "action3", "action4", "action5", "action6");
    }

    /** Agent registering more sibling actions than async workers. */
    public static class CapacityAgent extends Agent {

        public CapacityAgent() {
            for (int i = 1; i <= CAPACITY_ACTIONS; i++) {
                addAction(
                        new String[] {InputEvent.EVENT_TYPE},
                        method(CapacityAgent.class, "action" + i));
            }
        }

        public static void action1(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "action1");
        }

        public static void action2(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "action2");
        }

        public static void action3(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "action3");
        }

        public static void action4(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "action4");
        }

        public static void action5(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "action5");
        }

        public static void action6(Event event, RunnerContext ctx) throws Exception {
            executeAndEmit(event, ctx, "action6");
        }

        private static void executeAndEmit(Event event, RunnerContext ctx, String action)
                throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            String marker =
                    durableTiming(
                            ctx,
                            "capacity-" + action,
                            action,
                            request.key,
                            request.recordId,
                            1,
                            CAPACITY_SLEEP_MS);
            ctx.sendEvent(new OutputEvent(marker));
        }
    }

    @Test
    @Timeout(20)
    public void asyncFailureFailsTheJob() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<TestRequest> inputStream = env.fromElements(new TestRequest(1, 1));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new TestRequestKeySelector())
                        .apply(new FailureAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        Throwable failure = catchThrowable(agentsEnv::execute);
        if (failure == null) {
            results.close();
        }

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining(FAILURE_MARKER);
    }

    @Test
    @Timeout(20)
    public void failingSiblingFailsTheJobWithoutHanging() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<TestRequest> inputStream = env.fromElements(new TestRequest(1, 1));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new TestRequestKeySelector())
                        .apply(new MultiSiblingFailureAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        Throwable failure = catchThrowable(agentsEnv::execute);
        if (failure == null) {
            results.close();
        }

        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining(FAILURE_MARKER);
    }

    /** Agent whose single action always fails on the async worker. */
    public static class FailureAgent extends Agent {

        public FailureAgent() {
            addAction(new String[] {InputEvent.EVENT_TYPE}, method(FailureAgent.class, "fail"));
        }

        public static void fail(Event event, RunnerContext ctx) throws Exception {
            durableFailure(ctx, "scenario-failure", FAILURE_MARKER);
        }
    }

    /** Agent pairing a healthy sibling with a failing one on the same input. */
    public static class MultiSiblingFailureAgent extends Agent {

        public MultiSiblingFailureAgent() {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    method(MultiSiblingFailureAgent.class, "succeed"));
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    method(MultiSiblingFailureAgent.class, "fail"));
        }

        public static void succeed(Event event, RunnerContext ctx) throws Exception {
            String result =
                    ctx.durableExecuteAsync(
                                    new DurableCallable<String>() {
                                        @Override
                                        public String getId() {
                                            return "scenario-sibling-success";
                                        }

                                        @Override
                                        public Class<String> getResultClass() {
                                            return String.class;
                                        }

                                        @Override
                                        public String call() {
                                            return "sibling-success";
                                        }
                                    })
                            .await();
            ctx.sendEvent(new OutputEvent(result));
        }

        public static void fail(Event event, RunnerContext ctx) throws Exception {
            durableFailure(ctx, "scenario-sibling-failure", FAILURE_MARKER + "-sibling");
        }
    }

    @Test
    @Timeout(25)
    public void maxInFlightInputRecordsThrottlesAdmission() throws Exception {
        // Input admission backpressure is only enforced by the JDK<21 blocking-inline engine.
        Assumptions.assumeFalse(
                ContinuationActionExecutor.isContinuationSupported(),
                "MAX_IN_FLIGHT_INPUT_RECORDS is only enforced by the JDK<21 blocking-inline"
                        + " engine");
        List<String> outputs =
                runJob(
                        new BackpressureAgent(),
                        agentsEnv -> {
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentExecutionOptions.NUM_ASYNC_THREADS,
                                            BACKPRESSURE_ASYNC_THREADS);
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentExecutionOptions.MAX_IN_FLIGHT_INPUT_RECORDS,
                                            BACKPRESSURE_MAX_IN_FLIGHT);
                        },
                        new TestRequest(1, 1),
                        new TestRequest(2, 2));

        assertThat(outputs).containsExactlyInAnyOrder("1:run", "2:run");

        List<Span> spans = TimingLog.spans();
        spans.sort(Comparator.comparing(span -> span.recordId));
        assertThat(spans).hasSize(2);
        assertThat(spans).extracting(span -> span.recordId).containsExactly("1", "2");

        Span first = spans.get(0);
        Span second = spans.get(1);
        assertThat(second.startMs)
                .as("The second different-key input must wait for the first input to retire")
                .isGreaterThanOrEqualTo(first.endMs);
    }

    /** Agent with a single action; admission throttling happens before it runs. */
    public static class BackpressureAgent extends Agent {

        public BackpressureAgent() {
            addAction(new String[] {InputEvent.EVENT_TYPE}, method(BackpressureAgent.class, "run"));
        }

        public static void run(Event event, RunnerContext ctx) throws Exception {
            TestRequest request = (TestRequest) InputEvent.fromEvent(event).getInput();
            String marker =
                    durableTiming(
                            ctx,
                            "backpressure-" + request.recordId,
                            "run",
                            request.key,
                            request.recordId,
                            1,
                            BACKPRESSURE_SLEEP_MS);
            ctx.sendEvent(new OutputEvent(marker));
        }
    }
}
