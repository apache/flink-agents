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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.LlmJudgeRoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a chat request's target into a {@link ResolvedModelRoute}: if {@code model} names a
 * {@link ModelRouter}, runs its strategy inside a durable {@code "route:<router>"} call, emits the
 * observability-only {@link ModelRoutingEvent}, and normalizes the decision; otherwise returns the
 * direct route.
 */
final class ModelRoutingResolver {

    /** The durable-call id for the persisted routing decision — ONE definition for both paths. */
    private static String routeCallId(String model) {
        return "route:" + model;
    }

    private ModelRoutingResolver() {}

    /**
     * If {@code model} names a {@link ModelRouter}, run its strategy (as a durable {@code "route"}
     * call so the decision replays deterministically on recovery), normalize the result (abstain ->
     * default model, non-candidate -> fail clearly), emit an observability-only {@link
     * ModelRoutingEvent}, and return the selected concrete model. Otherwise returns a direct
     * selection.
     *
     * <p>Routing runs once for the initial chat request; tool-call rounds reuse the selected
     * concrete model because it is saved in the tool-request context (see {@code
     * ChatModelAction#handleToolCalls}), so this method is only reached with a router name on the
     * initial request.
     */
    static ResolvedModelRoute resolve(
            UUID requestId,
            String model,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            RunnerContext ctx)
            throws Exception {
        if (!ctx.hasResource(model, ResourceType.MODEL_ROUTER)) {
            return ResolvedModelRoute.direct(model);
        }
        ModelRouter router = (ModelRouter) ctx.getResource(model, ResourceType.MODEL_ROUTER);
        RoutingContext routingContext =
                new RoutingContext(requestId, model, messages, promptArgs, router.getCandidates());

        if (router.getStrategy() instanceof LlmJudgeRoutingStrategy) {
            return resolveViaJudge(
                    requestId,
                    model,
                    router,
                    (LlmJudgeRoutingStrategy) router.getStrategy(),
                    routingContext,
                    ctx);
        }

        DurableCallable<RoutingDecision> routeCallable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        // Deterministic across recovery re-processing: the durable store already
                        // scopes call results by (key, sequence number, event, action), so the id
                        // must NOT embed the request id — event ids are regenerated when Flink
                        // rolls back and re-processes, and a non-deterministic id turns every
                        // replay lookup into a miss (measured: 0/138 decisions replayed).
                        return routeCallId(model);
                    }

                    @Override
                    public Class<RoutingDecision> getResultClass() {
                        return RoutingDecision.class;
                    }

                    @Override
                    public RoutingDecision call() throws Exception {
                        // Timed inside the durable call so the latency is persisted with the
                        // decision: a replayed run reports the original strategy wall time.
                        long start = System.nanoTime();
                        RoutingDecision decision = router.route(routingContext);
                        return decision.withDecisionMs((System.nanoTime() - start) / 1_000_000.0);
                    }
                };

        RoutingDecision decision = ctx.durableExecute(routeCallable);
        recordDecisionLatency(ctx, decision);
        return normalizeAndFinish(
                requestId, model, router, decision, ModelRoutingEvent.SOURCE_STRATEGY, ctx);
    }

    /**
     * Shared post-durable handling for both the strategy and judge paths: abstain resolves to the
     * router's <i>current</i> default (so a persisted abstain replays gracefully across candidate
     * changes), a concrete selection is guarded against the current candidate set, and the
     * observability event and resolved route are emitted.
     */
    private static ResolvedModelRoute normalizeAndFinish(
            UUID requestId,
            String model,
            ModelRouter router,
            RoutingDecision decision,
            String concreteSource,
            RunnerContext ctx) {
        String selectedModel;
        String decisionSource;
        if (decision.isAbstain()) {
            selectedModel = router.getDefaultModel().orElse(router.getCandidateNames().get(0));
            decisionSource = ModelRoutingEvent.SOURCE_DEFAULT;
        } else {
            selectedModel = decision.getSelectedModel();
            if (!router.isCandidate(selectedModel)) {
                throw new IllegalStateException(
                        String.format(
                                "Routing decision for router '%s' selected non-candidate model '%s'; candidates are %s.",
                                model, selectedModel, router.getCandidateNames()));
            }
            decisionSource = concreteSource;
        }
        return finish(requestId, model, router, decision, selectedModel, decisionSource, ctx);
    }

    /** Records the decision latency histogram sample (also for decisions the guards reject). */
    private static void recordDecisionLatency(RunnerContext ctx, RoutingDecision decision) {
        Double decisionMs = decision.getDecisionMs();
        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        if (actionMetrics != null && decisionMs != null) {
            actionMetrics.getHistogram("routingDecisionLatencyMs").update(Math.round(decisionMs));
        }
    }

    /**
     * LLM-as-judge path (framework-managed, per discussion #897): the engine runs the judge chat
     * itself through the normal durable/metered/observable invoker path — durable id {@code
     * "judge:<router>"} so a recovered run replays the original verdict instead of re-calling the
     * judge (with a durable action-state store configured; without one the judge re-runs on replay,
     * like any non-deterministic strategy) — then derives the decision from the verdict as a pure
     * function. The decision (including its wall time, which covers the judge call) is persisted
     * under the standard {@code "route:<router>"} durable call, preserving the replay-fingerprint
     * property. Verdict abstains are persisted <i>as abstains</i>, so a replay after a
     * candidate-set change re-resolves to the current default exactly like the strategy path.
     *
     * <p>Failure policy: an unparseable or non-candidate verdict always abstains to the router's
     * default model. A judge call that exhausts its retries honors the request's error-handling
     * strategy, exactly like a throwing rule/custom strategy: {@code FAIL} surfaces the outage
     * loudly, {@code IGNORE} degrades to the default with the cause recorded. Interrupts
     * (cancellation) propagate and are never persisted as routing outcomes.
     */
    private static ResolvedModelRoute resolveViaJudge(
            UUID requestId,
            String model,
            ModelRouter router,
            LlmJudgeRoutingStrategy judge,
            RoutingContext routingContext,
            RunnerContext ctx)
            throws Exception {
        long start = System.nanoTime();
        Agent.ErrorHandlingStrategy errorStrategy =
                ctx.getConfig().get(AgentExecutionOptions.ERROR_HANDLING_STRATEGY);
        int numRetries = ChatModelInvoker.configuredRetries(ctx, errorStrategy);
        int retryWaitIntervalSec = ChatModelInvoker.configuredRetryWaitSec(ctx, errorStrategy);

        Map<String, Object> judgeMetadata = new LinkedHashMap<>();
        judgeMetadata.put("judge_model", judge.getJudgeModel());
        String verdictModel = null;
        String abstainReason = null;
        // A misconfigured judge follows the same policy as a failed judge call: FAIL is loud
        // (a config error should not hide), IGNORE abstains so the default model keeps
        // answering. Under IGNORE a replayed request is unaffected either way — the stored
        // decision below wins over the freshly computed abstain.
        String misconfigured = judgeSetupMisconfiguration(judge.getJudgeModel(), ctx);
        if (misconfigured != null && errorStrategy != Agent.ErrorHandlingStrategy.IGNORE) {
            throw new IllegalStateException(misconfigured);
        }
        if (misconfigured != null) {
            abstainReason = misconfigured;
        } else {
            try {
                ChatModelInvoker.ChatAttemptResult judgeResult =
                        ChatModelInvoker.chatWithRetries(
                                requestId,
                                judge.getJudgeModel(),
                                "judge:" + model,
                                judge.buildJudgeMessages(routingContext),
                                Map.of(),
                                null,
                                ctx,
                                errorStrategy,
                                numRetries,
                                retryWaitIntervalSec);
                ChatModelAction.recordAttemptRetryStats(
                        ctx,
                        requestId,
                        judgeResult.chatModel,
                        judgeResult.retryCount,
                        judgeResult.totalRetryWaitSec);
                ChatMessage reply = judgeResult.response;
                Object promptTokens = reply.getExtraArgs().get("promptTokens");
                Object completionTokens = reply.getExtraArgs().get("completionTokens");
                if (promptTokens != null) {
                    judgeMetadata.put("judge_prompt_tokens", promptTokens);
                }
                if (completionTokens != null) {
                    judgeMetadata.put("judge_completion_tokens", completionTokens);
                }
                verdictModel =
                        judge.parseVerdict(reply.getContent(), router.getCandidateNames())
                                .orElse(null);
                abstainReason =
                        verdictModel == null ? "judge verdict was not a candidate name" : null;
            } catch (InterruptedException cancellation) {
                // Cancellation surfacing from the between-retries backoff sleep.
                Thread.currentThread().interrupt();
                throw cancellation;
            } catch (ChatModelInvoker.ChatAttemptFailed failure) {
                ChatModelAction.recordAttemptRetryStats(
                        ctx,
                        requestId,
                        failure.chatModel,
                        failure.retryCount,
                        failure.totalRetryWaitSec);
                // Cancellation surfacing from inside the judge attempt (the invoker wraps every
                // attempt exception): it must propagate, never persist as a routing outcome.
                if (containsInterrupt(failure)) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                // A judge that exhausted its retries honors the request's error-handling strategy,
                // exactly like a throwing rule/custom strategy (see class javadoc).
                if (errorStrategy != Agent.ErrorHandlingStrategy.IGNORE) {
                    throw failure;
                }
                abstainReason = "judge call failed: " + failure.error;
            }
        }

        RoutingDecision computed;
        if (verdictModel != null) {
            RoutingDecision.Builder builder =
                    RoutingDecision.builder(verdictModel).reason("llm judge verdict");
            builder.metadata(
                    ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_LLM_JUDGE);
            for (Map.Entry<String, Object> entry : judgeMetadata.entrySet()) {
                builder.metadata(entry.getKey(), entry.getValue());
            }
            computed = builder.build();
        } else {
            // Persisted as a real abstain: replay resolves to the router's *current* default, so
            // a candidate-set change across a restart degrades gracefully (like the strategy
            // path) instead of failing the non-candidate guard.
            Map<String, Object> abstainMetadata = new LinkedHashMap<>(judgeMetadata);
            abstainMetadata.put(
                    ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_DEFAULT);
            computed = new RoutingDecision(null, true, abstainReason, null, abstainMetadata, null);
        }
        final RoutingDecision toStore =
                computed.withDecisionMs((System.nanoTime() - start) / 1_000_000.0);

        // Persist under the standard route id: on recovery the stored decision (with its original
        // judge-inclusive wall time) replays; the judge chat above replays from its own durable
        // record, so the recomputation feeding this call is deterministic.
        RoutingDecision decision =
                ctx.durableExecute(
                        new DurableCallable<>() {
                            @Override
                            public String getId() {
                                return routeCallId(model);
                            }

                            @Override
                            public Class<RoutingDecision> getResultClass() {
                                return RoutingDecision.class;
                            }

                            @Override
                            public RoutingDecision call() {
                                return toStore;
                            }
                        });
        recordDecisionLatency(ctx, decision);
        return normalizeAndFinish(
                requestId, model, router, decision, ModelRoutingEvent.SOURCE_LLM_JUDGE, ctx);
    }

    /**
     * The judge must be a plain chat model — nothing may rewrite the judge conversation. A bound
     * prompt would prepend an (unfilled) task prompt ahead of the verdict contract, bound tools
     * divert the reply into tool calls, and skills inject both a discovery prompt and tools — each
     * silently breaks verdict parsing on every request. Returns a diagnostic when misconfigured,
     * {@code null} when the setup is plain (or cannot be resolved — an unresolvable judge takes the
     * ChatAttemptFailed path with its normal policy).
     */
    private static String judgeSetupMisconfiguration(String judgeModel, RunnerContext ctx) {
        BaseChatModelSetup judgeSetup;
        try {
            judgeSetup = (BaseChatModelSetup) ctx.getResource(judgeModel, ResourceType.CHAT_MODEL);
        } catch (Exception resolutionHandledByInvoker) {
            return null;
        }
        List<String> skills = judgeSetup.getSkills();
        if (skills != null && !skills.isEmpty()) {
            return String.format(
                    "Judge model '%s' has skills %s configured; Strategies.llm requires a plain"
                            + " chat model (register the judge without skills).",
                    judgeModel, skills);
        }
        if (judgeSetup.getPrompt() != null) {
            return String.format(
                    "Judge model '%s' has a bound prompt; Strategies.llm requires a plain"
                            + " chat model (register the judge without a prompt).",
                    judgeModel);
        }
        List<String> toolNames = judgeSetup.getToolNames();
        if (toolNames != null && !toolNames.isEmpty()) {
            return String.format(
                    "Judge model '%s' has bound tools %s; Strategies.llm requires a plain"
                            + " chat model (register the judge without tools).",
                    judgeModel, toolNames);
        }
        return null;
    }

    /**
     * Whether the failed attempt was caused by thread interruption (cancellation) — including the
     * shapes HTTP stacks surface it as, which carry no {@link InterruptedException} in the chain.
     */
    static boolean containsInterrupt(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 64; t = t.getCause(), depth++) {
            // SocketTimeoutException extends InterruptedIOException but is an ordinary network
            // timeout, not a cancellation — it must keep following the failure policy.
            if (t instanceof InterruptedException
                    || (t instanceof java.io.InterruptedIOException
                            && !(t instanceof java.net.SocketTimeoutException))
                    || t instanceof java.nio.channels.ClosedByInterruptException
                    || t instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Shared tail: observability event + resolved route. */
    private static ResolvedModelRoute finish(
            UUID requestId,
            String model,
            ModelRouter router,
            RoutingDecision decision,
            String selectedModel,
            String decisionSource,
            RunnerContext ctx) {
        Double decisionMs = decision.getDecisionMs();
        ctx.sendEvent(
                new ModelRoutingEvent(
                        requestId,
                        model,
                        router.getCandidateNames(),
                        selectedModel,
                        decisionSource,
                        router.isFallbackEnabled(),
                        decision.getReason(),
                        decision.getScore(),
                        decision.getMetadata(),
                        decisionMs));
        return new ResolvedModelRoute(
                model,
                selectedModel,
                router.getCandidateNames(),
                true,
                router.isFallbackEnabled(),
                decisionSource,
                decision.getReason(),
                decision.getScore(),
                decision.getMetadata());
    }
}
