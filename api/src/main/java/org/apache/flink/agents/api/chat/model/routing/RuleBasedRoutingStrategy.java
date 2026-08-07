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

package org.apache.flink.agents.api.chat.model.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Built-in keyword/regex routing strategy. Configured with a map of {@code candidateModel ->
 * regex}; the first candidate whose regex matches the most recent user message (case-insensitive
 * find) wins, evaluated in the map's iteration order (pass a {@code LinkedHashMap} when precedence
 * matters). If nothing matches, the strategy abstains so the router uses its default model.
 *
 * <p>Constructed reflectively from a {@link RoutingStrategyDescriptor} via the {@code
 * (Map<String,Object>)} constructor; use {@link Strategies#rules(Map)} to build one.
 */
public class RuleBasedRoutingStrategy implements RoutingStrategy {

    private static final long serialVersionUID = 1L;

    private final Map<String, Pattern> rules;

    @SuppressWarnings("unchecked")
    public RuleBasedRoutingStrategy(Map<String, Object> args) {
        this.rules = new LinkedHashMap<>();
        Object raw = args == null ? null : args.get("rules");
        if (raw instanceof Map) {
            for (Map.Entry<String, ?> entry : ((Map<String, ?>) raw).entrySet()) {
                String candidate = entry.getKey();
                String regex = String.valueOf(entry.getValue());
                if (candidate != null && !candidate.isEmpty() && regex != null) {
                    this.rules.put(candidate, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
                }
            }
        }
    }

    @Override
    public RoutingDecision route(RoutingContext context) {
        String text = context.lastUserMessage();
        if (text != null && !text.isEmpty()) {
            for (Map.Entry<String, Pattern> entry : rules.entrySet()) {
                if (entry.getValue().matcher(text).find()) {
                    if (!isCandidate(entry.getKey(), context)) {
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

    private static boolean isCandidate(String model, RoutingContext context) {
        for (RoutingCandidate candidate : context.getCandidates()) {
            if (candidate.getName().equals(model)) {
                return true;
            }
        }
        return false;
    }
}
