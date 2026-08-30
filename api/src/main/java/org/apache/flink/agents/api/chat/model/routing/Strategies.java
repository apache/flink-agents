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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Factories for built-in routing strategies. Each returns a {@link RoutingStrategyDescriptor}
 * (class name + args) rather than a live instance, so the strategy is plan-serializable. There is
 * no magic-string strategy dispatch — the factory supplies the class name.
 */
public final class Strategies {

    private Strategies() {}

    /**
     * Keyword/regex rules: a map of {@code candidateModel -> regex}. The first candidate whose
     * regex matches the most recent user message wins; otherwise the strategy abstains (router
     * falls back to its default model).
     *
     * <p>Rules are evaluated in the map's iteration order, so when precedence between overlapping
     * patterns matters, pass a {@link java.util.LinkedHashMap} — {@code Map.of(...)} iteration
     * order is unspecified.
     */
    public static RoutingStrategyDescriptor rules(Map<String, String> rules) {
        Map<String, Object> args = new HashMap<>();
        args.put("rules", rules == null ? Collections.emptyMap() : rules);
        return new RoutingStrategyDescriptor(RuleBasedRoutingStrategy.class.getName(), args);
    }

    /**
     * A custom strategy referenced by class. The class must be a {@link RoutingStrategy} with
     * either a {@code (Map<String,Object>)} constructor or a no-arg constructor. This is the
     * deployable shape for custom routing.
     */
    public static RoutingStrategyDescriptor of(Class<? extends RoutingStrategy> clazz) {
        return new RoutingStrategyDescriptor(clazz.getName(), Collections.emptyMap());
    }

    /** A custom strategy referenced by class name plus construction arguments. */
    public static RoutingStrategyDescriptor of(String clazz, Map<String, Object> args) {
        return new RoutingStrategyDescriptor(clazz, args);
    }
}
