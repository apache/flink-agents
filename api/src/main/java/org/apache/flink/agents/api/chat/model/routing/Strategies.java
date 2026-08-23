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
     * LLM-as-judge routing (framework-managed): a judge chat model — registered like any other
     * {@code CHAT_MODEL} resource — reads the request and picks one candidate. The engine executes
     * the judge call on its durable, metered, observable chat path; the verdict is constrained to
     * candidate names. An unparseable or non-candidate verdict abstains to the router's default
     * model; a judge call that exhausts its retries honors the request's error-handling strategy
     * ({@code FAIL} surfaces it, {@code IGNORE} abstains with the cause recorded). The judge must
     * be a plain chat model — no bound prompt or tools. Candidate {@code describe(...)}
     * descriptions become the judge's decision criteria.
     */
    public static RoutingStrategyDescriptor llm(String judgeModel) {
        Map<String, Object> args = new HashMap<>();
        args.put(LlmJudgeRoutingStrategy.ARG_JUDGE_MODEL, judgeModel);
        return new RoutingStrategyDescriptor(LlmJudgeRoutingStrategy.class.getName(), args);
    }

    /**
     * Like {@link #llm(String)}, with a custom judge system prompt. The template may contain a
     * {@code {candidates}} placeholder, replaced with the candidate list (names + descriptions).
     * The template owns the verdict contract: the judge must still reply {@code {"model":
     * "<candidate name>"}} (or exactly a candidate name) to be parsed.
     */
    public static RoutingStrategyDescriptor llm(String judgeModel, String promptTemplate) {
        Map<String, Object> args = new HashMap<>();
        args.put(LlmJudgeRoutingStrategy.ARG_JUDGE_MODEL, judgeModel);
        args.put(LlmJudgeRoutingStrategy.ARG_PROMPT_TEMPLATE, promptTemplate);
        return new RoutingStrategyDescriptor(LlmJudgeRoutingStrategy.class.getName(), args);
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
