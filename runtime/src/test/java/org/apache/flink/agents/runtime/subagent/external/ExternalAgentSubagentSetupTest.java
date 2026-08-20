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

package org.apache.flink.agents.runtime.subagent.external;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.agents.runtime.subagent.BaseAsyncSubagentSetup;
import org.apache.flink.agents.runtime.subagent.BaseDeferredSubagentSetup;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises both sub-agent execution modes against an external async-task agent service over real
 * HTTP: {@link BaseAsyncSubagentSetup} (pub/sub: durable POST, then probe and fetch) and {@link
 * BaseDeferredSubagentSetup} (deferred: the request is issued when the handle resolves). Each mode
 * runs a success prompt and a failure prompt — the prompt describes the expected behavior, and the
 * mock backend fails on demand when it contains "fail". Both modes start probing right after the
 * submit and are asserted to check every call at least {@link #MIN_EXPECTED_CHECKS} times before it
 * turns terminal, which is what the probe interval is derived from.
 *
 * <p>By default the test starts {@link ExternalAgentStubService} on a loopback port, so it runs
 * anywhere with no service installed. Point {@code -Dexternal.agent.url=...} at a running service
 * to exercise the same suite against the real demo deployment instead; the suite is then skipped if
 * that endpoint is unreachable.
 */
public class ExternalAgentSubagentSetupTest {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalAgentSubagentSetupTest.class);

    /** Set to run against a service of your own instead of the in-process stub. */
    @Nullable private static final String EXTERNAL_URL = System.getProperty("external.agent.url");

    private static final String RESOURCE_NAME = "ext-agent";

    /** Prompt describing the expected successful behavior; the mock backend echoes it. */
    private static final String SUCCESS_PROMPT = "echo the greeting; expect a success";

    /** Prompt describing the expected failing behavior; the mock backend fails on "fail". */
    private static final String FAILURE_PROMPT = "please fail this run";

    /**
     * Every call must be checked at least this many times before reaching a terminal state, which
     * is what makes the polling of both modes observable rather than incidental.
     */
    private static final int MIN_EXPECTED_CHECKS = 5;

    /** How long a run of the stub service takes; the real demo service uses about five seconds. */
    private static final long STUB_TASK_DELAY_MILLIS = 1_500;

    /** The real demo service's task delay, used to pace the probes when running against it. */
    private static final long EXTERNAL_TASK_DELAY_MILLIS = 5_000;

    @Nullable private static ExternalAgentStubService stub;

    private static String baseUrl;

    /**
     * The probe interval, derived from the service's task delay so that a run is always checked
     * more than {@link #MIN_EXPECTED_CHECKS} times.
     */
    private static long probeIntervalMillis;

    /** The backend reported by the service; the mock backend answers deterministically. */
    private static String llmBackend;

    @BeforeAll
    static void startService() throws Exception {
        long taskDelayMillis;
        if (EXTERNAL_URL == null) {
            stub = ExternalAgentStubService.start(STUB_TASK_DELAY_MILLIS);
            baseUrl = stub.baseUrl();
            taskDelayMillis = STUB_TASK_DELAY_MILLIS;
        } else {
            baseUrl = EXTERNAL_URL;
            taskDelayMillis = EXTERNAL_TASK_DELAY_MILLIS;
        }
        probeIntervalMillis = taskDelayMillis / (MIN_EXPECTED_CHECKS + 1);
        ExternalAgentClient client = new ExternalAgentClient(baseUrl);
        Assumptions.assumeTrue(
                client.reachable(), "external agent service not reachable at " + baseUrl);
        llmBackend = client.llmBackend();
        LOG.info(
                "external agent service at {} is up, llm_backend={}, probing every {} ms",
                baseUrl,
                llmBackend,
                probeIntervalMillis);
    }

    @AfterAll
    static void stopService() {
        if (stub != null) {
            stub.close();
            stub = null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Mode 1: async pub/sub through BaseAsyncSubagentSetup
    // ------------------------------------------------------------------------------------------

    /** Submits the success prompt through the short form and awaits the run's outcome. */
    @SuppressWarnings("unused")
    public static void asyncSubmitAndAwait(Event event, RunnerContext ctx) throws Exception {
        BaseAsyncSubagentSetup setup =
                (BaseAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentResult result = setup.submit(ctx, SUCCESS_PROMPT).await();
        ctx.sendEvent(new OutputEvent(result.isSuccess() + "|" + result.getResult()));
    }

    @Test
    void asyncModeSucceedsAndChecksAtLeastFiveTimes() throws Exception {
        LOG.info("[test] async mode, success prompt: {}", SUCCESS_PROMPT);
        ExternalAsyncSubagentSetup setup = asyncSetup();
        long started = System.currentTimeMillis();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("asyncSubmitAndAwait", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .satisfies(
                            value -> assertSuccessfulEcho(String.valueOf(value), SUCCESS_PROMPT));
            // The probes are spread over the run rather than issued back-to-back.
            List<Long> probes = setup.probeTimestamps();
            assertThat(probes).hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_CHECKS);
            for (int i = 1; i < probes.size(); i++) {
                assertThat(probes.get(i) - probes.get(i - 1))
                        .describedAs("gap between check #%d and #%d", i, i + 1)
                        .isGreaterThanOrEqualTo(probeIntervalMillis / 2);
            }
            LOG.info(
                    "[test] async success done in {} ms, checks={}",
                    System.currentTimeMillis() - started,
                    probes.size());
        }
    }

    /** Submits the failure prompt; the remote run fails and the error surfaces in the Result. */
    @SuppressWarnings("unused")
    public static void asyncSubmitFailingPrompt(Event event, RunnerContext ctx) throws Exception {
        BaseAsyncSubagentSetup setup =
                (BaseAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentResult result = setup.submit(ctx, FAILURE_PROMPT).await();
        ctx.sendEvent(new OutputEvent(result.isSuccess() + "|" + result.getErrorMessage()));
    }

    @Test
    void asyncModeSurfacesARemoteFailureWithoutFetching() throws Exception {
        LOG.info("[test] async mode, failure prompt: {}", FAILURE_PROMPT);
        ExternalAsyncSubagentSetup setup = asyncSetup();
        long started = System.currentTimeMillis();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("asyncSubmitFailingPrompt", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .satisfies(
                            value -> {
                                String output = String.valueOf(value);
                                assertThat(output).startsWith("false|");
                                assertThat(output).contains("mock agent failed on demand");
                            });
            // The failing run is paced the same way, so it is checked as often.
            assertThat(setup.probeTimestamps()).hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_CHECKS);
            LOG.info("[test] async failure done in {} ms", System.currentTimeMillis() - started);
        }
    }

    /** Batches one success and one failure handle and resolves them together. */
    @SuppressWarnings("unused")
    public static void asyncBatchSuccessAndFailure(Event event, RunnerContext ctx)
            throws Exception {
        BaseAsyncSubagentSetup setup =
                (BaseAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture success = setup.submit(ctx, SUCCESS_PROMPT);
        SubagentFuture failure = setup.submit(ctx, FAILURE_PROMPT);
        List<SubagentResult> results = success.combine(failure).awaitAll();
        ctx.sendEvent(
                new OutputEvent(
                        results.get(0).isSuccess()
                                + "|"
                                + results.get(0).getResult()
                                + "||"
                                + results.get(1).isSuccess()
                                + "|"
                                + results.get(1).getErrorMessage()));
    }

    @Test
    void asyncModeCombineResolvesSuccessAndFailureTogether() throws Exception {
        LOG.info("[test] async mode, batched success + failure prompts");
        long started = System.currentTimeMillis();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("asyncBatchSuccessAndFailure", asyncSetup()))) {
            harness.open();
            run(harness, 1L);

            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .satisfies(
                            value -> {
                                String[] halves = String.valueOf(value).split("\\|\\|");
                                assertSuccessfulEcho(halves[0], SUCCESS_PROMPT);
                                assertThat(halves[1]).startsWith("false|");
                                assertThat(halves[1]).contains("mock agent failed on demand");
                            });
            LOG.info("[test] async batch done in {} ms", System.currentTimeMillis() - started);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Mode 2: deferred execution through BaseDeferredSubagentSetup
    // ------------------------------------------------------------------------------------------

    /** Resolves one deferred handle with the success prompt, issued at resolve time. */
    @SuppressWarnings("unused")
    public static void deferredSubmitAndAwait(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentResult result =
                setup.submit(ctx, SUCCESS_PROMPT, "session", "call-success").await();
        ctx.sendEvent(new OutputEvent(result.isSuccess() + "|" + result.getResult()));
    }

    @Test
    void deferredModeSucceedsAndChecksAtLeastFiveTimes() throws Exception {
        LOG.info("[test] deferred mode, success prompt: {}", SUCCESS_PROMPT);
        ExternalDeferredSubagentSetup setup = deferredSetup();
        long started = System.currentTimeMillis();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("deferredSubmitAndAwait", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .satisfies(
                            value -> assertSuccessfulEcho(String.valueOf(value), SUCCESS_PROMPT));
            // The polls are spread over the run, so it is checked at least as often.
            assertThat(setup.pollCount()).isGreaterThanOrEqualTo(MIN_EXPECTED_CHECKS);
            LOG.info(
                    "[test] deferred success done in {} ms, checks={}",
                    System.currentTimeMillis() - started,
                    setup.pollCount());
        }
    }

    /** Resolves one deferred handle with the failure prompt. */
    @SuppressWarnings("unused")
    public static void deferredSubmitFailingPrompt(Event event, RunnerContext ctx)
            throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentResult result =
                setup.submit(ctx, FAILURE_PROMPT, "session", "call-failure").await();
        ctx.sendEvent(new OutputEvent(result.isSuccess() + "|" + result.getErrorMessage()));
    }

    @Test
    void deferredModeSurfacesARemoteFailure() throws Exception {
        LOG.info("[test] deferred mode, failure prompt: {}", FAILURE_PROMPT);
        ExternalDeferredSubagentSetup setup = deferredSetup();
        long started = System.currentTimeMillis();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("deferredSubmitFailingPrompt", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .satisfies(
                            value -> {
                                String output = String.valueOf(value);
                                assertThat(output).startsWith("false|");
                                assertThat(output).contains("mock agent failed on demand");
                            });
            // The failing run is paced the same way, so it is checked as often.
            assertThat(setup.pollCount()).isGreaterThanOrEqualTo(MIN_EXPECTED_CHECKS);
            LOG.info("[test] deferred failure done in {} ms", System.currentTimeMillis() - started);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Mode 3: reconciliation across the crash window (idempotent resubmission)
    // ------------------------------------------------------------------------------------------

    /**
     * Simulates a failover after the POST landed but before its durable record was persisted: a
     * fresh setup instance, with no state from the crashed process, must find the original remote
     * task through the deterministic id, and its reconciler must not start a duplicate.
     */
    @Test
    void reconcileAfterCrashResumesTheOriginalTask() throws Exception {
        String sessionId = "reco-crash-" + UUID.randomUUID();
        String callId = "call-1";
        LOG.info("[test] reconcile after crash, session={}", sessionId);
        ExternalAgentClient client = new ExternalAgentClient(baseUrl);
        int tasksBefore = client.taskCount();

        ExternalAsyncSubagentSetup original = asyncSetup();
        original.callSubmitRequest(sessionId, callId, SUCCESS_PROMPT);
        assertThat(client.taskCount()).isEqualTo(tasksBefore + 1);

        // Failover: a brand-new setup instance with nothing in memory.
        ExternalAsyncSubagentSetup recovered = asyncSetup();
        assertThat(recovered.callQueryStatus(sessionId, callId).getState())
                .describedAs("the recovered setup must find the original in-flight task")
                .isEqualTo(BaseAsyncSubagentSetup.RunStatus.State.RUNNING);

        // The reconciler sees RUNNING and repairs nothing; no duplicate task appears.
        recovered.reconcileForTest(sessionId, callId, SUCCESS_PROMPT);
        assertThat(client.taskCount()).isEqualTo(tasksBefore + 1);

        // The recovered setup tracks the original task to its terminal result.
        BaseAsyncSubagentSetup.RunStatus probe = waitForTerminal(recovered, sessionId, callId);
        assertThat(probe.getState()).isEqualTo(BaseAsyncSubagentSetup.RunStatus.State.COMPLETED);
        SubagentResult result = recovered.callFetchResult(sessionId, callId);
        assertThat(result.isSuccess()).isTrue();
        assertSuccessfulEcho("true|" + result.getResult(), SUCCESS_PROMPT);
        assertThat(client.taskCount()).isEqualTo(tasksBefore + 1);
        LOG.info("[test] reconcile after crash done, original task resumed without duplication");
    }

    /**
     * Simulates a crash where the POST never landed: the reconciler's probe sees NOT_STARTED and
     * starts the run; a second reconciliation finds it RUNNING and adds no duplicate.
     */
    @Test
    void reconcileRepostsWhenThePostNeverLanded() throws Exception {
        String sessionId = "reco-lost-" + UUID.randomUUID();
        String callId = "call-1";
        LOG.info("[test] reconcile lost POST, session={}", sessionId);
        ExternalAgentClient client = new ExternalAgentClient(baseUrl);
        int tasksBefore = client.taskCount();

        ExternalAsyncSubagentSetup setup = asyncSetup();
        assertThat(setup.callQueryStatus(sessionId, callId).getState())
                .isEqualTo(BaseAsyncSubagentSetup.RunStatus.State.NOT_STARTED);

        // First reconciliation: nothing on the service, so the POST is (re)sent.
        setup.reconcileForTest(sessionId, callId, SUCCESS_PROMPT);
        assertThat(client.taskCount()).isEqualTo(tasksBefore + 1);
        assertThat(setup.callQueryStatus(sessionId, callId).getState())
                .isEqualTo(BaseAsyncSubagentSetup.RunStatus.State.RUNNING);

        // Second reconciliation: the run exists now; nothing is repaired or duplicated.
        setup.reconcileForTest(sessionId, callId, SUCCESS_PROMPT);
        assertThat(client.taskCount()).isEqualTo(tasksBefore + 1);
        LOG.info("[test] reconcile lost POST done, task started exactly once");
    }

    /** Probes until the run reaches a terminal state; fails after 15 seconds. */
    private static BaseAsyncSubagentSetup.RunStatus waitForTerminal(
            ExternalAsyncSubagentSetup setup, String sessionId, String callId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            BaseAsyncSubagentSetup.RunStatus probe = setup.callQueryStatus(sessionId, callId);
            switch (probe.getState()) {
                case COMPLETED:
                case FAILED:
                    return probe;
                default:
                    if (System.currentTimeMillis() > deadline) {
                        throw new AssertionError(
                                "task of "
                                        + sessionId
                                        + "#"
                                        + callId
                                        + " never reached a"
                                        + " terminal state");
                    }
                    Thread.sleep(500);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Assertions and harness plumbing
    // ------------------------------------------------------------------------------------------

    /**
     * Asserts a {@code true|<result>} output. The mock backend echoes deterministically, so its
     * answer is asserted exactly; a real LLM backend only needs a non-blank answer.
     */
    private static void assertSuccessfulEcho(String output, String prompt) {
        assertThat(output).startsWith("true|");
        String answer = output.substring("true|".length());
        if ("mock".equals(llmBackend)) {
            assertThat(answer).isEqualTo("[offline-mock] echo: " + prompt);
        } else {
            assertThat(answer).isNotBlank();
        }
    }

    @SuppressWarnings("unchecked")
    private static void run(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness, long value)
            throws Exception {
        harness.processElement(new StreamRecord<>(value));
        ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                .waitInFlightEventsFinished();
    }

    /** An async-mode setup against the running service, paced to the derived probe interval. */
    private static ExternalAsyncSubagentSetup asyncSetup() {
        return new ExternalAsyncSubagentSetup(baseUrl, probeIntervalMillis);
    }

    /** A deferred-mode setup against the running service, paced to the derived probe interval. */
    private static ExternalDeferredSubagentSetup deferredSetup() {
        return new ExternalDeferredSubagentSetup(baseUrl, probeIntervalMillis);
    }

    private static AgentPlan plan(String actionMethod, Object setup) throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, setup);
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ExternalAgentSubagentSetupTest.class.getMethod(
                        actionMethod, Event.class, RunnerContext.class));
        return new AgentPlan(agent, new AgentConfiguration());
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            AgentPlan plan) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true),
                (KeySelector<Long, Long>) value -> value,
                TypeExtractor.getForClass(Long.class));
    }
}
