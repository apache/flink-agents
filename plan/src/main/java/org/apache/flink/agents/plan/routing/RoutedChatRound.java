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

package org.apache.flink.agents.plan.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.actions.ChatModelInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One model round of a routed chat request: the attempts over the route's candidates until an
 * answer is accepted. The strategy's pick goes first. An attempt that fails after its retries falls
 * back to the next candidate the router allows; an answer a quality signal rejects is discarded,
 * before any of its tool calls runs, and the same conversation goes to the next tier, where the
 * request then stays. The routing events, the escalation metrics and the {@code model_routing}
 * block are produced here. {@code ChatModelAction} resolves the route, runs the round and processes
 * the accepted answer; it never sees the individual attempts.
 *
 * <p>The block is observability-only and needed once, on the final response. A round whose answer
 * starts or continues a tool loop parks it in sensory memory, keyed by the initial request id, and
 * the round that produces the final response takes it ({@link Accepted#parkForToolRound} and {@link
 * Accepted#finish}). A tool round of a tiered request keeps the escalation policy in force ({@link
 * #continuedRoute}) and folds its hops into the parked block.
 */
public final class RoutedChatRound {

    private static final Logger LOG = LoggerFactory.getLogger(RoutedChatRound.class);

    /** Sensory-memory key of the parked {@code model_routing} blocks, by initial request id. */
    static final String ROUTING_METADATA_CONTEXT = "_ROUTING_METADATA_CONTEXT";

    private RoutedChatRound() {}

    /** The accepted attempt of a round, with the routing block that belongs to the request. */
    public static final class Accepted {
        private final UUID requestId;

        /** The attempt whose answer was accepted: model, response and retry statistics. */
        public final ChatModelInvoker.ChatAttemptResult attempt;

        @Nullable private final Map<String, Object> routingMetadata;

        Accepted(
                UUID requestId,
                ChatModelInvoker.ChatAttemptResult attempt,
                @Nullable Map<String, Object> routingMetadata) {
            this.requestId = requestId;
            this.attempt = attempt;
            this.routingMetadata = routingMetadata;
        }

        /**
         * The answer starts or continues a tool loop: the block waits for the final response
         * instead of being stamped on intermediate messages and copied through every tool round.
         */
        public void parkForToolRound(MemoryObject sensoryMemory) throws Exception {
            if (routingMetadata != null) {
                park(sensoryMemory, requestId, routingMetadata);
            }
        }

        /**
         * The answer completes the request: the block to attach to the response, or null when the
         * request never went through a router. Removes whatever the request had parked.
         */
        @Nullable
        public Map<String, Object> finish(MemoryObject sensoryMemory) throws Exception {
            Map<String, Object> parked = take(sensoryMemory, requestId);
            return routingMetadata != null ? routingMetadata : parked;
        }
    }

    /** The round produced no accepted answer; {@link #error} is what the request fails with. */
    public static final class RoundFailed extends Exception {
        public final Exception error;

        RoundFailed(Exception error) {
            super(error.getMessage(), error);
            this.error = error;
        }
    }

    /**
     * Runs one round over the route's candidates.
     *
     * @throws RoundFailed when every reachable candidate failed, when a quality signal could not
     *     judge the round, or when every tier answered and was rejected; the request's parked block
     *     has been removed when it is thrown
     */
    public static Accepted run(
            UUID requestId,
            ResolvedModelRoute route,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx)
            throws Exception {
        int numRetries = ChatModelInvoker.configuredRetries(ctx);
        long retryWaitMillis = ChatModelInvoker.configuredRetryWaitMillis(ctx);

        List<String> triedModels = new ArrayList<>();
        // Candidates whose answer a quality signal rejected this round: fallback skips them, since
        // they already answered the request.
        Set<String> rejected = new HashSet<>();
        List<Map<String, Object>> hops = new ArrayList<>();
        long escalatedTokens = 0L;
        int escalations = route.getEscalationsSoFar();
        Deque<String> pending = new ArrayDeque<>(route.attemptOrder());
        // viaFallback: the candidate about to answer was reached because the previous one failed
        // (drives the fallback event); fallbackAttempted: any failure fell through this round.
        boolean viaFallback = false;
        boolean fallbackAttempted = false;
        String lastFailedModel = null;
        Exception lastError = null;
        while (!pending.isEmpty()) {
            String candidate = pending.pollFirst();
            if (rejected.contains(candidate)) {
                continue;
            }
            triedModels.add(candidate);
            try {
                ChatModelInvoker.ChatAttemptResult result =
                        ChatModelInvoker.chatWithRetries(
                                requestId,
                                candidate,
                                route.durableChatCallId(candidate),
                                messages,
                                promptArgs,
                                outputSchema,
                                ctx,
                                numRetries,
                                retryWaitMillis);
                ChatModelAction.recordAttemptRetryStats(
                        ctx, requestId, result.model, result.retryCount, result.totalRetryWaitMs);

                // Quality gate: on a tier below the last one, an answer that fails a structural
                // check or a declared signal is discarded before any of its tool calls runs, and
                // the conversation so far goes to the next tier, where the request then stays.
                Optional<Escalation> policy = route.getEscalation();
                if (policy.isPresent()) {
                    Optional<EscalationGate.Outcome> escalate;
                    try {
                        escalate =
                                EscalationGate.check(
                                        policy.get(),
                                        requestId,
                                        route.getRequestedModel(),
                                        candidate,
                                        escalations,
                                        messages,
                                        promptArgs,
                                        result,
                                        ctx);
                    } catch (ModelRoutingResolver.RoutingFailure e) {
                        // A signal that cannot judge the round (verifier out of retries, custom
                        // signal threw) fails the logical request, like a failed routing decision.
                        take(ctx.getSensoryMemory(), requestId);
                        throw new RoundFailed(e);
                    }
                    if (escalate.isPresent()) {
                        EscalationGate.Outcome outcome = escalate.get();
                        rejected.add(candidate);
                        escalations++;
                        long discarded = discardedTokens(result.response);
                        escalatedTokens += discarded;
                        hops.add(hop(outcome, discarded));
                        recordEscalationMetrics(ctx, discarded);
                        Map<String, Object> eventMetadata = new LinkedHashMap<>();
                        eventMetadata.put("signal", outcome.signal);
                        eventMetadata.put("escalated_from", outcome.fromTier);
                        eventMetadata.put("discarded_tokens", discarded);
                        ctx.sendEvent(
                                new ModelRoutingEvent(
                                        requestId,
                                        route.getRequestedModel(),
                                        route.getCandidates(),
                                        outcome.toTier,
                                        ModelRoutingEvent.SOURCE_ESCALATION,
                                        route.isFallbackEnabled(),
                                        outcome.reason,
                                        outcome.score,
                                        eventMetadata,
                                        null));
                        LOG.info(
                                "Chat request {} escalated from '{}' to '{}' on router '{}' ({}: {})",
                                requestId,
                                outcome.fromTier,
                                outcome.toTier,
                                route.getRequestedModel(),
                                outcome.signal,
                                outcome.reason);
                        pending.remove(outcome.toTier);
                        pending.addFirst(outcome.toTier);
                        viaFallback = false;
                        continue;
                    }
                }

                if (route.isRouter() && viaFallback) {
                    // The strategy's pick failed and another candidate answered; emit a
                    // routing Event to report the fallback outcome.
                    ctx.sendEvent(
                            new ModelRoutingEvent(
                                    requestId,
                                    route.getRequestedModel(),
                                    route.getCandidates(),
                                    result.model,
                                    ModelRoutingEvent.SOURCE_FALLBACK,
                                    route.isFallbackEnabled(),
                                    String.format(
                                            "fallback after model '%s' failed", lastFailedModel),
                                    null,
                                    route.getMetadata(),
                                    null));
                }

                // The block of a routed initial round is built here; a tool round of a tiered
                // request folds its hops into the block the initial round parked.
                Map<String, Object> routingMetadata = null;
                if (route.isRouter()) {
                    routingMetadata =
                            route.buildResponseMetadata(
                                    result.model,
                                    triedModels,
                                    fallbackAttempted,
                                    hops,
                                    escalatedTokens);
                } else if (policy.isPresent()) {
                    Map<String, Object> parked = peek(ctx.getSensoryMemory(), requestId);
                    if (parked != null) {
                        routingMetadata =
                                ResolvedModelRoute.mergeEscalations(
                                        parked, result.model, hops, escalatedTokens);
                    }
                }
                return new Accepted(requestId, result, routingMetadata);
            } catch (ChatModelInvoker.ChatAttemptFailed e) {
                ChatModelAction.recordAttemptRetryStats(
                        ctx, requestId, e.model, e.retryCount, e.totalRetryWaitMs);
                // Keep every candidate's failure: chain the previous error into the new one so
                // exhaustion surfaces A's and B's errors as suppressed of C's, not just C's.
                if (lastError != null && lastError != e.error) {
                    e.error.addSuppressed(lastError);
                }
                lastError = e.error;
                lastFailedModel = e.model;
                viaFallback = true;
                fallbackAttempted = true;
                LOG.debug(
                        "Chat request {} failed for model {} with error: {}. The input chat"
                                + " messages are {}.",
                        requestId,
                        e.model,
                        e.error.toString(),
                        messages);
            }
        }

        if (route.isRouter() && triedModels.size() > 1) {
            LOG.warn(
                    "Chat request {} exhausted all candidates {} of router '{}'; last error: {}.",
                    requestId,
                    triedModels,
                    route.getRequestedModel(),
                    lastError == null ? null : lastError.toString());
        }
        // The reasoning loop is over; a routed loop that dies mid-way must not leak its
        // parked block before the failed logical request completes.
        take(ctx.getSensoryMemory(), requestId);
        throw new RoundFailed(
                lastError != null
                        ? lastError
                        : new IllegalStateException(
                                "Every escalation tier of router '"
                                        + route.getRequestedModel()
                                        + "' answered and was rejected by the quality policy,"
                                        + " and no candidate is left to answer."));
    }

    /**
     * The route of a tool round: the already-selected model, with the router's escalation policy
     * still in force when the initial request was routed through a tiered router. Tool rounds never
     * re-route and never fall back; the policy is the one thing that can still move the request.
     */
    public static ResolvedModelRoute continuedRoute(
            MemoryObject sensoryMemory, UUID requestId, String model, RunnerContext ctx)
            throws Exception {
        Map<String, Object> parked = peek(sensoryMemory, requestId);
        if (parked == null || !(parked.get("router") instanceof String)) {
            return ResolvedModelRoute.direct(model);
        }
        String router = (String) parked.get("router");
        if (!ctx.hasResource(router, ResourceType.MODEL_ROUTER)) {
            return ResolvedModelRoute.direct(model);
        }
        ModelRouter resolved = (ModelRouter) ctx.getResource(router, ResourceType.MODEL_ROUTER);
        if (resolved.getEscalation().isEmpty()) {
            return ResolvedModelRoute.direct(model);
        }
        return ResolvedModelRoute.continued(
                router,
                model,
                resolved.getCandidateNames(),
                resolved.isFallbackEnabled(),
                resolved.getEscalation().get(),
                ResolvedModelRoute.recordedEscalations(parked));
    }

    /** Prompt plus completion tokens of a discarded attempt, 0 when the provider reported none. */
    private static long discardedTokens(@Nullable ChatResult response) {
        TokenUsage usage = response == null ? null : response.getUsage();
        if (usage == null) {
            return 0L;
        }
        long prompt = usage.getPromptTokens() == null ? 0L : usage.getPromptTokens();
        long completion = usage.getCompletionTokens() == null ? 0L : usage.getCompletionTokens();
        return prompt + completion;
    }

    private static Map<String, Object> hop(EscalationGate.Outcome outcome, long discardedTokens) {
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("from", outcome.fromTier);
        hop.put("to", outcome.toTier);
        hop.put("signal", outcome.signal);
        hop.put("reason", outcome.reason);
        if (outcome.score != null) {
            hop.put("score", outcome.score);
        }
        hop.put("discarded_tokens", discardedTokens);
        return hop;
    }

    private static void recordEscalationMetrics(RunnerContext ctx, long discardedTokens) {
        FlinkAgentsMetricGroup metrics = ctx.getActionMetricGroup();
        if (metrics != null) {
            metrics.getCounter("routingEscalationCount").inc();
            metrics.getCounter("routingEscalatedTokens").inc(discardedTokens);
        }
    }

    /** Parks the request's block; replaces the one parked by an earlier round. */
    @SuppressWarnings("unchecked")
    private static void park(MemoryObject sensoryMemory, UUID requestId, Map<String, Object> block)
            throws Exception {
        Map<UUID, Object> context;
        if (sensoryMemory.isExist(ROUTING_METADATA_CONTEXT)) {
            context = (Map<UUID, Object>) sensoryMemory.get(ROUTING_METADATA_CONTEXT).getValue();
        } else {
            context = new HashMap<>();
        }
        context.put(requestId, block);
        sensoryMemory.set(ROUTING_METADATA_CONTEXT, context);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> peek(MemoryObject sensoryMemory, UUID requestId)
            throws Exception {
        if (!sensoryMemory.isExist(ROUTING_METADATA_CONTEXT)) {
            return null;
        }
        Map<UUID, Object> context =
                (Map<UUID, Object>) sensoryMemory.get(ROUTING_METADATA_CONTEXT).getValue();
        return (Map<String, Object>) context.get(requestId);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> take(MemoryObject sensoryMemory, UUID requestId)
            throws Exception {
        if (!sensoryMemory.isExist(ROUTING_METADATA_CONTEXT)) {
            return null;
        }
        Map<UUID, Object> context =
                (Map<UUID, Object>) sensoryMemory.get(ROUTING_METADATA_CONTEXT).getValue();
        Map<String, Object> block = (Map<String, Object>) context.remove(requestId);
        if (block != null) {
            sensoryMemory.set(ROUTING_METADATA_CONTEXT, context);
        }
        return block;
    }
}
