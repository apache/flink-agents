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
 * {@link ModelRouter}, runs its strategy inside a durable {@code "route:<router>"} call, emits the
 * observability-only {@link ModelRoutingEvent}, and normalizes the decision; otherwise returns the
 * direct route.
 */
final class ModelRoutingResolver {

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

        DurableCallable<RoutingDecision> routeCallable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        // Deterministic across recovery re-processing: the durable store already
                        // scopes call results by (key, sequence number, event, action), so the id
                        // must NOT embed the request id — event ids are regenerated when Flink
                        // rolls back and re-processes, and a non-deterministic id turns every
                        // replay lookup into a miss (measured: 0/138 decisions replayed).
                        return "route:" + model;
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
        Double decisionMs = decision.getDecisionMs();
        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        if (actionMetrics != null && decisionMs != null) {
            actionMetrics.getHistogram("routingDecisionLatencyMs").update(Math.round(decisionMs));
        }

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
                                "Routing strategy for router '%s' returned non-candidate model '%s'; candidates are %s.",
                                model, selectedModel, router.getCandidateNames()));
            }
            decisionSource = ModelRoutingEvent.SOURCE_STRATEGY;
        }

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
