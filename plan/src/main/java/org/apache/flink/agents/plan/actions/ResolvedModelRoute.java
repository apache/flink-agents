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

import org.apache.flink.agents.api.event.ModelRoutingEvent;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of route resolution for one chat request: the concrete model to call first, the
 * candidate set and fallback policy, and the decision facts (source, reason, score, metadata) that
 * feed the {@code model_routing} observability block. A plain (unrouted) request is the degenerate
 * {@link #direct(String)} route.
 */
final class ResolvedModelRoute {
    final String requestedModel;
    final String selectedModel;
    final List<String> candidates;
    final boolean isRouter;
    final boolean fallbackEnabled;
    final String decisionSource;
    @Nullable final String reason;
    @Nullable final Double score;
    final Map<String, Object> metadata;

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

    static ResolvedModelRoute direct(String model) {
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
    List<String> attemptOrder() {
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

    String durableChatCallId(String candidate) {
        if (!this.isRouter) {
            return "chat";
        }
        return "chat:" + this.requestedModel + ":" + candidate;
    }

    /** The {@code model_routing} extra-args block stamped on the loop's final response. */
    Map<String, Object> buildResponseMetadata(String finalModel, List<String> triedModels) {
        boolean fallbackAttempted = !finalModel.equals(this.selectedModel);
        List<String> fallbackModelsTried = new ArrayList<>();
        for (int i = 1; i < triedModels.size(); i++) {
            fallbackModelsTried.add(triedModels.get(i));
        }
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("router", this.requestedModel);
        routing.put("selected_model", this.selectedModel);
        routing.put("final_model", finalModel);
        routing.put("candidates", new ArrayList<>(this.candidates));
        routing.put(
                "decision_source",
                fallbackAttempted ? ModelRoutingEvent.SOURCE_FALLBACK : this.decisionSource);
        routing.put("fallback_enabled", this.fallbackEnabled);
        routing.put("fallback_attempted", fallbackAttempted);
        routing.put("fallback_models_tried", fallbackModelsTried);
        routing.put("metadata", new LinkedHashMap<>(this.metadata));
        if (this.reason != null) {
            routing.put("reason", this.reason);
        }
        if (this.score != null) {
            routing.put("score", this.score);
        }
        return routing;
    }
}
