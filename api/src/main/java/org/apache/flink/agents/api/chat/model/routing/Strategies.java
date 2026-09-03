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
 * Factories for routing-strategy declarations. Each returns a {@link RoutingStrategy} — a
 * serializable declaration (type + arguments), not executable logic — so the strategy travels in
 * the agent plan as a language-neutral tag. There is no magic-string strategy dispatch — the
 * factory supplies the type.
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
    public static RoutingStrategy rules(Map<String, String> rules) {
        Map<String, Object> args = new HashMap<>();
        args.put(RoutingStrategy.ARG_RULES, rules == null ? Collections.emptyMap() : rules);
        return new RoutingStrategy(RoutingStrategyType.RULE_BASED, args, null);
    }

    /**
     * LLM-as-judge routing (framework-managed): a judge chat model — registered like any other
     * {@code CHAT_MODEL} resource — reads the request and picks one candidate. The engine executes
     * the judge call on its durable, metered, observable chat path; the verdict is constrained to
     * candidate names. An unparseable or non-candidate verdict abstains to the router's default
     * model; a judge call that exhausts its retries honors the request's error-handling strategy
     * ({@code FAIL} surfaces it, {@code IGNORE} abstains with the cause recorded). Candidate {@code
     * describe(...)} descriptions become the judge's decision criteria.
     *
     * <p><b>Judge model contract:</b> the judge must be a plain chat model — no prompt, tools, or
     * skills. Descriptor-declared bindings are rejected at plan construction. A {@code
     * ChatModelSetup} implementation that adds them dynamically without declaring them in its
     * descriptor is an invalid judge: its replies stop parsing as verdicts and every request
     * abstains to the router's default model (visible in the routing events as abstains).
     *
     * <p>The judge receives the complete message list (and the rendered request when the target
     * setup binds a prompt); use {@link RoutingStrategy#withMaxContextChars(int)} to cap the
     * context for cost.
     */
    public static RoutingStrategy llm(String judgeModel) {
        Map<String, Object> args = new HashMap<>();
        args.put(RoutingStrategy.ARG_JUDGE_MODEL, judgeModel);
        return new RoutingStrategy(RoutingStrategyType.LLM_JUDGE, args, null);
    }

    /**
     * Like {@link #llm(String)}, with a custom judge system prompt. The template may contain a
     * {@code {candidates}} placeholder, replaced with the candidate list (names + descriptions).
     * The template owns the verdict contract: the judge must still reply {@code {"model":
     * "<candidate name>"}} (or exactly a candidate name) to be parsed.
     */
    public static RoutingStrategy llm(String judgeModel, String promptTemplate) {
        Map<String, Object> args = new HashMap<>();
        args.put(RoutingStrategy.ARG_JUDGE_MODEL, judgeModel);
        args.put(RoutingStrategy.ARG_PROMPT_TEMPLATE, promptTemplate);
        return new RoutingStrategy(RoutingStrategyType.LLM_JUDGE, args, null);
    }

    /**
     * A custom executor referenced by class. The class must implement {@link CustomRoutingExecutor}
     * with either a {@code (Map<String,Object>)} constructor or a no-arg constructor. This is the
     * deployable shape for custom routing.
     */
    public static RoutingStrategy custom(Class<? extends CustomRoutingExecutor> executorClass) {
        return new RoutingStrategy(
                RoutingStrategyType.CUSTOM, Collections.emptyMap(), executorClass.getName());
    }

    /** A custom executor referenced by class, with construction arguments. */
    public static RoutingStrategy custom(
            Class<? extends CustomRoutingExecutor> executorClass, Map<String, Object> args) {
        return new RoutingStrategy(RoutingStrategyType.CUSTOM, args, executorClass.getName());
    }

    /** A custom executor referenced by class name plus construction arguments. */
    public static RoutingStrategy custom(String executorClass, Map<String, Object> args) {
        return new RoutingStrategy(RoutingStrategyType.CUSTOM, args, executorClass);
    }
}
