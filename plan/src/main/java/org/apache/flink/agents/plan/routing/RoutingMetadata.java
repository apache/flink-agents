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

import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.event.ModelRoutingEvent;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code model_routing} block of a routed request: how it is assembled from the route and the
 * outcome of a round, and where it waits between the rounds of a tool loop. The block is response
 * metadata, attached once, to the final response, so a round whose answer starts or continues a
 * tool loop keeps it in sensory memory, keyed by the initial request id, instead of stamping it on
 * intermediate messages. While it waits there it is also the request's routing state: the next
 * round reads the router's name and the hops already taken from it, to keep the escalation policy
 * in force. {@link RoutedChatRound} produces and stores the block through this class; {@link
 * ResolvedModelRoute} only carries the facts it is built from.
 */
public final class RoutingMetadata {

    /** Sensory-memory key of the saved blocks, by initial request id. */
    static final String ROUTING_METADATA_CONTEXT = "_ROUTING_METADATA_CONTEXT";

    /** Key of the router's name in the block. */
    public static final String ROUTER_KEY = "router";

    /** Key of the hop list in the block. */
    public static final String ESCALATIONS_KEY = "escalations";

    /** Key of the discarded-attempt token total in the block. */
    public static final String ESCALATED_TOKENS_KEY = "escalated_tokens";

    /** Key of the escalation flag in the block. */
    public static final String ESCALATION_ATTEMPTED_KEY = "escalation_attempted";

    private RoutingMetadata() {}

    /**
     * The block of a round: {@code fallbackAttempted} is an actual fallback attempt (a candidate
     * answered after another failed), {@code hops} the escalations taken (each a map with {@code
     * from}, {@code to}, {@code signal}, {@code reason} and optional {@code score}) and {@code
     * escalatedTokens} the tokens of the discarded attempts. {@code decision_source} records the
     * last step that changed the model.
     */
    public static Map<String, Object> build(
            ResolvedModelRoute route,
            String finalModel,
            List<String> triedModels,
            boolean fallbackAttempted,
            List<Map<String, Object>> hops,
            long escalatedTokens) {
        List<String> fallbackModelsTried = new ArrayList<>();
        List<String> escalatedTo = new ArrayList<>();
        for (Map<String, Object> hop : hops) {
            escalatedTo.add(String.valueOf(hop.get("to")));
        }
        for (int i = 1; i < triedModels.size(); i++) {
            if (!escalatedTo.contains(triedModels.get(i))) {
                fallbackModelsTried.add(triedModels.get(i));
            }
        }
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put(ROUTER_KEY, route.requestedModel);
        routing.put("selected_model", route.selectedModel);
        routing.put("final_model", finalModel);
        routing.put("candidates", new ArrayList<>(route.candidates));
        // The last step that changed the model: an escalation whose target answered, else the
        // fallback that stepped in after a failure, else the routing decision.
        String source = route.decisionSource;
        if (escalatedTo.contains(finalModel)) {
            source = ModelRoutingEvent.SOURCE_ESCALATION;
        } else if (fallbackAttempted) {
            source = ModelRoutingEvent.SOURCE_FALLBACK;
        }
        routing.put(ModelRoutingEvent.DECISION_SOURCE_KEY, source);
        routing.put("fallback_enabled", route.fallbackEnabled);
        routing.put("fallback_attempted", fallbackAttempted);
        routing.put("fallback_models_tried", fallbackModelsTried);
        routing.put(ESCALATION_ATTEMPTED_KEY, !hops.isEmpty());
        routing.put(ESCALATIONS_KEY, new ArrayList<>(hops));
        routing.put(ESCALATED_TOKENS_KEY, escalatedTokens);
        routing.put("metadata", new LinkedHashMap<>(route.metadata));
        if (route.reason != null) {
            routing.put("reason", route.reason);
        }
        if (route.score != null) {
            routing.put("score", route.score);
        }
        return routing;
    }

    /**
     * Folds the hops of a later round into the block saved when a tiered request started its tool
     * loop: the final model, the appended hop list, the token total and the source.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeEscalations(
            Map<String, Object> saved,
            String finalModel,
            List<Map<String, Object>> hops,
            long escalatedTokens) {
        Map<String, Object> routing = new LinkedHashMap<>(saved);
        routing.put("final_model", finalModel);
        List<Map<String, Object>> all = new ArrayList<>();
        Object existing = saved.get(ESCALATIONS_KEY);
        if (existing instanceof List) {
            all.addAll((List<Map<String, Object>>) existing);
        }
        all.addAll(hops);
        routing.put(ESCALATIONS_KEY, all);
        routing.put(ESCALATION_ATTEMPTED_KEY, !all.isEmpty());
        Object tokens = saved.get(ESCALATED_TOKENS_KEY);
        long total = tokens instanceof Number ? ((Number) tokens).longValue() : 0L;
        routing.put(ESCALATED_TOKENS_KEY, total + escalatedTokens);
        if (!hops.isEmpty()) {
            routing.put(ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_ESCALATION);
        }
        return routing;
    }

    /** The router a saved block was produced by, or null when there is no block or no name. */
    @Nullable
    public static String routerOf(@Nullable Map<String, Object> saved) {
        Object router = saved == null ? null : saved.get(ROUTER_KEY);
        return router instanceof String ? (String) router : null;
    }

    /** Hops already recorded in a saved block. */
    @SuppressWarnings("unchecked")
    public static int recordedEscalations(@Nullable Map<String, Object> saved) {
        if (saved == null) {
            return 0;
        }
        Object existing = saved.get(ESCALATIONS_KEY);
        return existing instanceof List ? ((List<Object>) existing).size() : 0;
    }

    /** Saves the request's block; replaces the one saved by an earlier round. */
    @SuppressWarnings("unchecked")
    static void save(MemoryObject sensoryMemory, UUID requestId, Map<String, Object> block)
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

    /** The request's saved block, left in place; null when there is none. */
    @SuppressWarnings("unchecked")
    @Nullable
    static Map<String, Object> peek(MemoryObject sensoryMemory, UUID requestId) throws Exception {
        if (!sensoryMemory.isExist(ROUTING_METADATA_CONTEXT)) {
            return null;
        }
        Map<UUID, Object> context =
                (Map<UUID, Object>) sensoryMemory.get(ROUTING_METADATA_CONTEXT).getValue();
        return (Map<String, Object>) context.get(requestId);
    }

    /** Removes and returns the request's saved block; null when there is none. */
    @SuppressWarnings("unchecked")
    @Nullable
    static Map<String, Object> take(MemoryObject sensoryMemory, UUID requestId) throws Exception {
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
