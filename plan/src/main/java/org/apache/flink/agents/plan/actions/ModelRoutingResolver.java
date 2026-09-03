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
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategyType;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves a chat request's target into a {@link ResolvedModelRoute}: if {@code model} names a
 * {@link ModelRouter}, executes the declared strategy — dispatched by its language-neutral {@link
 * RoutingStrategyType} — inside the durable {@code "route:<router>"} boundary, emits the
 * observability-only {@link ModelRoutingEvent}, and normalizes the decision; otherwise returns the
 * direct route.
 *
 * <p>The durable boundary is owned here, not by the executors: every decision — rules, custom,
 * judge — is persisted by this class, so replay determinism is a property of the substrate rather
 * than a per-executor discipline.
 */
final class ModelRoutingResolver {

    /** The durable-call id for the persisted routing decision — ONE definition for all paths. */
    private static String routeCallId(String model) {
        return "route:" + model;
    }

    private ModelRoutingResolver() {}

    /**
     * If {@code model} names a {@link ModelRouter}, execute its declared strategy (persisted under
     * the durable {@code "route"} call so the decision replays deterministically on recovery),
     * normalize the result (abstain -> default model, non-candidate -> fail clearly), emit an
     * observability-only {@link ModelRoutingEvent}, and return the selected concrete model.
     * Otherwise returns a direct selection.
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
        RoutingStrategy strategy = router.getStrategy();

        if (strategy.getType() == RoutingStrategyType.LLM_JUDGE) {
            return resolveViaJudge(requestId, model, router, strategy, routingContext, ctx);
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
                        // decision: a replayed run reports the original strategy wall time — and
                        // the strategy is never re-executed on replay.
                        long start = System.nanoTime();
                        RoutingDecision decision = executePure(router, strategy, routingContext);
                        return decision.withDecisionMs((System.nanoTime() - start) / 1_000_000.0);
                    }
                };

        RoutingDecision decision = ctx.durableExecute(routeCallable);
        recordDecisionLatency(ctx, decision);
        return normalizeAndFinish(
                requestId, model, router, decision, ModelRoutingEvent.SOURCE_STRATEGY, ctx);
    }

    /** Executes the pure (engine-free) strategy types: built-in rules, or the user's executor. */
    private static RoutingDecision executePure(
            ModelRouter router, RoutingStrategy strategy, RoutingContext routingContext)
            throws Exception {
        switch (strategy.getType()) {
            case RULE_BASED:
                return executeRules(router, routingContext);
            case CUSTOM:
                return router.getCustomExecutor().route(strategy, routingContext);
            default:
                throw new IllegalStateException(
                        "Unhandled routing strategy type: " + strategy.getType());
        }
    }

    /**
     * Built-in keyword/regex rules: the first candidate whose pattern (pre-compiled by the router)
     * matches the most recent user message wins, in declaration order; no match abstains so the
     * router uses its default model.
     */
    private static RoutingDecision executeRules(ModelRouter router, RoutingContext context) {
        String text = context.lastUserMessage();
        if (text != null && !text.isEmpty()) {
            for (Map.Entry<String, Pattern> entry : router.getCompiledRules().entrySet()) {
                if (entry.getValue().matcher(text).find()) {
                    if (!router.isCandidate(entry.getKey())) {
                        throw new IllegalArgumentException(
                                "Routing rule selected non-candidate model '"
                                        + entry.getKey()
                                        + "'.");
                    }
                    return RoutingDecision.builder(entry.getKey())
                            .reason("matched rule: " + entry.getValue().pattern())
                            .build();
                }
            }
        }
        return RoutingDecision.abstain();
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
     * function ({@link LlmJudgeRoutingExecutor}). The decision (including its wall time, which
     * covers the judge call) is persisted under the standard {@code "route:<router>"} durable call,
     * preserving the replay-fingerprint property. Verdict abstains are persisted <i>as
     * abstains</i>, so a replay after a candidate-set change re-resolves to the current default
     * exactly like the strategy path.
     *
     * <p>Failure policy: an unparseable or non-candidate verdict always abstains to the router's
     * default model. A judge call that exhausts its retries honors the request's error-handling
     * strategy, exactly like a throwing rule/custom strategy: {@code FAIL} surfaces the outage
     * loudly, {@code IGNORE} degrades to the default with the cause recorded. Cancellation
     * propagates and is never persisted as a routing outcome.
     */
    private static ResolvedModelRoute resolveViaJudge(
            UUID requestId,
            String model,
            ModelRouter router,
            RoutingStrategy strategy,
            RoutingContext routingContext,
            RunnerContext ctx)
            throws Exception {
        long start = System.nanoTime();
        Agent.ErrorHandlingStrategy errorStrategy =
                ctx.getConfig().get(AgentExecutionOptions.ERROR_HANDLING_STRATEGY);
        int numRetries = ChatModelInvoker.configuredRetries(ctx, errorStrategy);
        int retryWaitIntervalSec = ChatModelInvoker.configuredRetryWaitSec(ctx, errorStrategy);
        String judgeModel = LlmJudgeRoutingExecutor.judgeModel(strategy);

        Map<String, Object> judgeMetadata = new LinkedHashMap<>();
        judgeMetadata.put("judge_model", judgeModel);
        String verdictModel = null;
        String abstainReason = null;
        // Runtime backstop to the plan-time check: plan validation only sees descriptor-carried
        // bindings, so a setup that binds a prompt/tools/skills at the instance level (or via a
        // non-Java provider) is caught here. Same policy as a failed judge call: FAIL is loud
        // (a config error should not hide), IGNORE abstains so the default model keeps answering.
        String misconfigured = judgeSetupMisconfiguration(judgeModel, ctx);
        if (misconfigured != null && errorStrategy != Agent.ErrorHandlingStrategy.IGNORE) {
            throw new IllegalStateException(misconfigured);
        }
        if (misconfigured != null) {
            abstainReason = misconfigured;
        } else
            try {
                boolean[] truncated = new boolean[1];
                List<ChatMessage> effective = effectiveJudgeMessages(router, routingContext, ctx);
                List<ChatMessage> judgeInput =
                        LlmJudgeRoutingExecutor.buildJudgeMessages(
                                strategy,
                                routingContext,
                                effective,
                                pinnedRenderedIndices(routingContext.getMessages(), effective),
                                truncated);
                if (truncated[0]) {
                    judgeMetadata.put(LlmJudgeRoutingExecutor.CONTEXT_TRUNCATED_KEY, true);
                }
                ChatModelInvoker.ChatAttemptResult judgeResult =
                        ChatModelInvoker.chatWithRetries(
                                requestId,
                                judgeModel,
                                "judge:" + model,
                                judgeInput,
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
                // Same both-or-neither type guard as the metrics reader of these extraArgs
                // keys (ChatModelAction#recordChatTokenMetrics): a half-populated or non-Number
                // pair must not leak into the durable decision metadata.
                Object promptTokens = reply.getExtraArgs().get("promptTokens");
                Object completionTokens = reply.getExtraArgs().get("completionTokens");
                if (promptTokens instanceof Number && completionTokens instanceof Number) {
                    judgeMetadata.put("judge_prompt_tokens", promptTokens);
                    judgeMetadata.put("judge_completion_tokens", completionTokens);
                }
                verdictModel =
                        LlmJudgeRoutingExecutor.parseVerdict(
                                        reply.getContent(), router.getCandidateNames())
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
                if (isCancellation(failure)) {
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

        RoutingDecision computed;
        if (verdictModel != null) {
            RoutingDecision.Builder builder =
                    RoutingDecision.builder(verdictModel).reason("llm judge verdict");
            for (Map.Entry<String, Object> entry : judgeMetadata.entrySet()) {
                builder.metadata(entry.getKey(), entry.getValue());
            }
            computed = builder.build();
        } else {
            // Persisted as a real abstain: replay resolves to the router's *current* default, so
            // a candidate-set change across a restart degrades gracefully (like the strategy
            // path) instead of failing the non-candidate guard.
            computed =
                    new RoutingDecision(
                            null, true, abstainReason, null, new HashMap<>(judgeMetadata), null);
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
     * The judge routes on what the selected model will actually receive. When the target setup
     * binds a {@link Prompt}, this mirrors {@code BaseChatModelSetup#chat}: the template is
     * rendered with the request's prompt args and prepended to the non-empty conversation messages.
     * The rendering anchor is the router's default candidate (or the first candidate) — where
     * abstains resolve, and in practice the workload-level prompt shared by the candidates. If the
     * anchor can't be resolved or binds no prompt, the raw message list is used unchanged.
     */
    private static List<ChatMessage> effectiveJudgeMessages(
            ModelRouter router, RoutingContext routingContext, RunnerContext ctx) {
        List<ChatMessage> messages = routingContext.getMessages();
        String anchor = router.getDefaultModel().orElse(router.getCandidateNames().get(0));
        try {
            BaseChatModelSetup setup =
                    (BaseChatModelSetup) ctx.getResource(anchor, ResourceType.CHAT_MODEL);
            // One shared implementation with the chat path (prepareRequestMessages), so the
            // judge's view cannot drift from what the selected model receives. Candidates binding
            // DIFFERENT prompts see their own rendering only at answer time — the anchor (default
            // candidate, where abstains resolve) is a documented approximation.
            return setup.prepareRequestMessages(messages, routingContext.getPromptArgs());
        } catch (Exception unresolvable) {
            // An unresolvable candidate surfaces on the real chat path with its normal policy.
            return messages;
        }
    }

    /**
     * Indices of effective messages that were <i>generated</i> by the anchor's request shaping
     * (rendered template, skill-discovery prompt) rather than taken from the conversation —
     * identified by object identity, since {@code prepareRequestMessages} appends the original
     * message instances unchanged. They carry the task definition, so the context cap pins them.
     */
    private static java.util.Set<Integer> pinnedRenderedIndices(
            List<ChatMessage> original, List<ChatMessage> effective) {
        if (effective == original) {
            return java.util.Set.of();
        }
        java.util.Set<ChatMessage> originals =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        originals.addAll(original);
        java.util.Set<Integer> pinned = new java.util.LinkedHashSet<>();
        for (int i = 0; i < effective.size(); i++) {
            if (!originals.contains(effective.get(i))) {
                pinned.add(i);
            }
        }
        return pinned;
    }

    /**
     * The judge must be a plain chat model — nothing may rewrite the judge conversation. A bound
     * prompt would prepend an (unfilled) task prompt ahead of the verdict contract, bound tools
     * divert the reply into tool calls, and skills inject both a discovery prompt and tools — each
     * silently breaks verdict parsing on every request. Plan-time validation catches
     * descriptor-carried bindings; this backstop catches instance-level ones. Returns a diagnostic
     * when misconfigured, {@code null} when the setup is plain (or cannot be resolved — an
     * unresolvable judge takes the ChatAttemptFailed path with its normal policy).
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
     * Whether the failed attempt was caused by cancellation. Determined from the thread's interrupt
     * state plus the explicit cancellation types; IO shapes like {@code InterruptedIOException} are
     * deliberately NOT treated as cancellation — HTTP stacks (e.g. Okio) raise a bare {@code
     * InterruptedIOException("timeout")} for ordinary network timeouts, which must keep following
     * the normal failure policy.
     */
    static boolean isCancellation(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 64; t = t.getCause(), depth++) {
            if (t instanceof InterruptedException
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
