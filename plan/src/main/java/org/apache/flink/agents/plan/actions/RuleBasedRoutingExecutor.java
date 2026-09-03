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

import org.apache.flink.agents.api.chat.model.routing.RoutingCandidate;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.event.ModelRoutingEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Executes {@code Strategies.rules(...)}: the first candidate whose pattern matches the most recent
 * user message wins, in declaration order; no match abstains so the router uses its default model.
 *
 * <p>Compilation lives here — not in the API-layer {@code ModelRouter} — because a compiled {@code
 * Map<String, Pattern>} is a Java execution detail of the plan layer, while the router carries only
 * the language-neutral declaration. Patterns are compiled once per declaration and cached (weakly
 * keyed by the {@link RoutingStrategy} instance, whose lifetime matches the per-subtask router
 * cache), so rule evaluation stays regex-match-only per request. Declaration validity (shape,
 * types, regex syntax) is enforced by the {@link RoutingStrategy} constructor before any executor
 * sees it.
 */
final class RuleBasedRoutingExecutor implements RoutingExecutor {

    /**
     * Compiled patterns per declaration instance. Weak keys: an entry is reclaimed with its router
     * (which strongly holds the strategy) when the per-subtask resource cache evicts it.
     * Synchronized because executors are shared across operator subtask threads in one JVM.
     */
    private final Map<RoutingStrategy, Map<String, Pattern>> compiledCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public String decisionSource() {
        return ModelRoutingEvent.SOURCE_STRATEGY;
    }

    @Override
    public RoutingDecision route(
            RoutingStrategy strategy,
            RoutingContext context,
            org.apache.flink.agents.api.context.RunnerContext ctx) {
        String text = context.lastUserMessage();
        if (text != null && !text.isEmpty()) {
            for (Map.Entry<String, Pattern> entry : compiledRules(strategy).entrySet()) {
                if (entry.getValue().matcher(text).find()) {
                    if (!isCandidate(context, entry.getKey())) {
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

    private static boolean isCandidate(RoutingContext context, String model) {
        for (RoutingCandidate candidate : context.getCandidates()) {
            if (candidate.getName().equals(model)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Pattern> compiledRules(RoutingStrategy strategy) {
        return compiledCache.computeIfAbsent(strategy, RuleBasedRoutingExecutor::compile);
    }

    /** Compiles the (constructor-validated) rule map, preserving declaration order. */
    private static Map<String, Pattern> compile(RoutingStrategy strategy) {
        Object raw = strategy.getArguments().get(RoutingStrategy.ARG_RULES);
        if (!(raw instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            compiled.put(
                    (String) entry.getKey(),
                    Pattern.compile((String) entry.getValue(), Pattern.CASE_INSENSITIVE));
        }
        return Collections.unmodifiableMap(compiled);
    }
}
