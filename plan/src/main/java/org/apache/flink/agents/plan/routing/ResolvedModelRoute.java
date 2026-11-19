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

import org.apache.flink.agents.api.chat.model.routing.Escalation;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The outcome of route resolution for one chat request: the concrete model to call first, the
 * candidate set and fallback policy, and the decision facts (source, reason, score, metadata) that
 * {@link RoutingMetadata} turns into the {@code model_routing} observability block. A plain
 * (unrouted) request is the degenerate {@link #direct(String)} route.
 */
public final class ResolvedModelRoute {
    final String requestedModel;
    final String selectedModel;
    final List<String> candidates;
    final boolean isRouter;
    final boolean fallbackEnabled;
    final String decisionSource;
    @Nullable final String reason;
    @Nullable final Double score;
    final Map<String, Object> metadata;
    @Nullable final Escalation escalation;
    final int escalationsSoFar;

    ResolvedModelRoute(
            String requestedModel,
            String selectedModel,
            List<String> candidates,
            boolean isRouter,
            boolean fallbackEnabled,
            String decisionSource,
            @Nullable String reason,
            @Nullable Double score,
            @Nullable Map<String, Object> metadata) {
        this(
                requestedModel,
                selectedModel,
                candidates,
                isRouter,
                fallbackEnabled,
                decisionSource,
                reason,
                score,
                metadata,
                null,
                0);
    }

    ResolvedModelRoute(
            String requestedModel,
            String selectedModel,
            List<String> candidates,
            boolean isRouter,
            boolean fallbackEnabled,
            String decisionSource,
            @Nullable String reason,
            @Nullable Double score,
            @Nullable Map<String, Object> metadata,
            @Nullable Escalation escalation,
            int escalationsSoFar) {
        this.escalation = escalation;
        this.escalationsSoFar = escalationsSoFar;
        this.requestedModel = requestedModel;
        this.selectedModel = selectedModel;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.isRouter = isRouter;
        this.fallbackEnabled = fallbackEnabled;
        this.decisionSource = decisionSource;
        this.reason = reason;
        this.score = score;
        this.metadata =
                metadata == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /** The model name the action was asked for: a router name or a plain chat model. */
    public String getRequestedModel() {
        return requestedModel;
    }

    /** The candidate the strategy selected (or the requested model when not routing). */
    public String getSelectedModel() {
        return selectedModel;
    }

    /** The router's declared candidates, in declaration order; a singleton when not routing. */
    public List<String> getCandidates() {
        return candidates;
    }

    /** Whether the requested model was a router. */
    public boolean isRouter() {
        return isRouter;
    }

    /** Whether failed attempts may fall through to the remaining candidates. */
    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    /** Strategy-supplied decision metadata, never null. */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** The router's escalation policy, when the request is tiered. */
    public Optional<Escalation> getEscalation() {
        return Optional.ofNullable(escalation);
    }

    /** Hops already taken by this request in earlier rounds (continuation routes only). */
    public int getEscalationsSoFar() {
        return escalationsSoFar;
    }

    /**
     * A tool round of a tiered request: no routing decision and no candidate fallback, as for any
     * tool round (the attempt order is the current tier alone), but the escalation policy stays in
     * force so a weak round can still move the request up a tier. {@code model} is the tier the
     * request currently sits on; {@code fallbackEnabled} is the router's setting, carried for the
     * routing events this round may emit.
     */
    public static ResolvedModelRoute continued(
            String router,
            String model,
            List<String> candidates,
            boolean fallbackEnabled,
            Escalation escalation,
            int escalationsSoFar) {
        return new ResolvedModelRoute(
                router,
                model,
                candidates,
                false,
                fallbackEnabled,
                "direct",
                null,
                null,
                null,
                escalation,
                escalationsSoFar);
    }

    public static ResolvedModelRoute direct(String model) {
        return new ResolvedModelRoute(
                model,
                model,
                Collections.singletonList(model),
                false,
                false,
                "direct",
                null,
                null,
                null);
    }

    /** Candidate order: the strategy's pick first, then declaration order if fallback is on. */
    public List<String> attemptOrder() {
        List<String> order = new ArrayList<>();
        order.add(this.selectedModel);
        if (this.isRouter && this.fallbackEnabled) {
            for (String candidate : this.candidates) {
                if (!candidate.equals(this.selectedModel)) {
                    order.add(candidate);
                }
            }
        }
        return order;
    }

    public String durableChatCallId(String candidate) {
        if (this.isRouter) {
            return "chat:" + this.requestedModel + ":" + candidate;
        }
        if (this.escalation != null && !candidate.equals(this.selectedModel)) {
            // A tool round escalated to another tier: the current tier's attempt keeps the plain
            // "chat" id that tool rounds always had; the escalated attempt gets its own.
            return "chat:" + this.requestedModel + ":" + candidate;
        }
        return "chat";
    }
}
