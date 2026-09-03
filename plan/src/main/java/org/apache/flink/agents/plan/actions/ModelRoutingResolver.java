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

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a chat request's target into a {@link ResolvedModelRoute}: if {@code model} names a
 * {@link ModelRouter}, resolves the {@link RoutingExecutor} for the declared strategy type (via
 * {@link RoutingExecutors} — the resolver itself has no type-based dispatch), runs it, persists the
 * decision under the durable {@code "route:<router>"} boundary, emits the observability-only {@link
 * ModelRoutingEvent}, and normalizes the decision; otherwise returns the direct route.
 *
 * <p>The durable boundary is owned here, not by the executors: every decision — rules, custom,
 * judge — is persisted by this class, so replay determinism is a property of the substrate rather
 * than a per-executor discipline. Executors without durable sub-calls run <i>inside</i> the
 * persistence callable (never re-invoked on replay); an executor that issues its own flat durable
 * calls ({@link RoutingExecutor#issuesDurableCalls()}) runs <i>before</i> it, because the durable
 * substrate replays a flat, order-matched call sequence and cannot nest (see the sequencing
 * contract on {@link RoutingExecutor}).
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
                new RoutingContext(
                        requestId,
                        model,
                        messages,
                        promptArgs,
                        router.getCandidates(),
                        router.getDefaultModel().orElse(null));
        RoutingStrategy strategy = router.getStrategy();
        RoutingExecutor executor = RoutingExecutors.forType(strategy.getType());

        // Outside the persistence boundary: a transiently failing preparation (e.g. a custom
        // executor's constructor) must throw fresh per request, never persist as the decision.
        executor.prepare(strategy, ctx);

        RoutingDecision decision =
                executor.issuesDurableCalls()
                        ? persistPrecomputed(executor, strategy, routingContext, model, ctx)
                        : executeInsideBoundary(executor, strategy, routingContext, model, ctx);
        recordDecisionLatency(ctx, decision);
        return normalizeAndFinish(
                requestId, model, router, decision, executor.decisionSource(), ctx);
    }

    /**
     * Executors without durable sub-calls run inside the persistence callable: on replay the stored
     * decision short-circuits the callable, so the executor is never re-invoked (a custom
     * executor's side effects do not re-execute).
     */
    private static RoutingDecision executeInsideBoundary(
            RoutingExecutor executor,
            RoutingStrategy strategy,
            RoutingContext routingContext,
            String model,
            RunnerContext ctx)
            throws Exception {
        return ctx.durableExecute(
                routeDecisionCallable(
                        model,
                        () -> {
                            // Timed inside the durable call so the latency is persisted with the
                            // decision: a replayed run reports the original strategy wall time —
                            // and the strategy is never re-executed on replay.
                            long start = System.nanoTime();
                            RoutingDecision decision =
                                    executor.route(strategy, routingContext, ctx);
                            return decision.withDecisionMs(
                                    (System.nanoTime() - start) / 1_000_000.0);
                        }));
    }

    /**
     * The single definition of the {@code route:<router>} persistence record — both execution
     * shapes persist through this callable, so the id scheme and result class cannot diverge.
     */
    private static DurableCallable<RoutingDecision> routeDecisionCallable(
            String model, java.util.concurrent.Callable<RoutingDecision> body) {
        return new DurableCallable<>() {
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
                return body.call();
            }
        };
    }

    /**
     * Executors with their own flat durable calls (the judge's chat) run before the persistence
     * call so their records land as flat siblings ahead of the decision record. On recovery the
     * executor re-runs against its replayed sub-call records (deterministic and cheap — the judge
     * chat itself is never re-called), and the stored decision — with its original wall time —
     * wins.
     */
    private static RoutingDecision persistPrecomputed(
            RoutingExecutor executor,
            RoutingStrategy strategy,
            RoutingContext routingContext,
            String model,
            RunnerContext ctx)
            throws Exception {
        long start = System.nanoTime();
        RoutingDecision computed = executor.route(strategy, routingContext, ctx);
        final RoutingDecision toStore =
                computed.withDecisionMs((System.nanoTime() - start) / 1_000_000.0);
        return ctx.durableExecute(routeDecisionCallable(model, () -> toStore));
    }

    /**
     * Shared post-durable handling for all executors: abstain resolves to the router's
     * <i>current</i> default (so a persisted abstain replays gracefully across candidate changes),
     * a concrete selection is guarded against the current candidate set, and the observability
     * event and resolved route are emitted.
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
