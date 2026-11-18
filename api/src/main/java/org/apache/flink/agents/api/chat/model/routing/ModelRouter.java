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

import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Declares a model router: the candidate chat models, how a request's first model is picked and,
 * optionally, the tiers it may escalate through. The declaration is data only — candidates plus the
 * strategy as a language-neutral type tag and arguments — so it is plan-serializable across
 * runtimes. {@link #of(String...)} starts the fluent builder and {@link Builder#build()} produces
 * the {@link ResourceDescriptor} to register as a {@code MODEL_ROUTER} resource.
 *
 * <p>The descriptor names this class ({@link ResourceName.ModelRouter#MODEL_ROUTER}), the
 * declaration name shared with the Python API, the YAML loader and the cross-language plan format.
 * The resource the engine instantiates from it lives in the plan module ({@code
 * org.apache.flink.agents.plan.routing.ModelRouter}): the plan maps the declaration name to that
 * class and validates the descriptor when the agent plan is built, so the resource only decodes it.
 *
 * <p>Abstain ({@link RoutingDecision#abstain()}) → the default model. A returned name that is not a
 * candidate is treated as abstain, with the rejected name kept in the routing event's metadata.
 * Declaration errors (a non-candidate default or rule target) fail at build time.
 */
public final class ModelRouter {

    /** Descriptor key carrying the candidate model names. */
    public static final String CANDIDATES_KEY = "candidates";

    /** Descriptor key carrying the strategy type tag ({@link RoutingStrategyType#tag()}). */
    public static final String STRATEGY_TYPE_KEY = "strategy_type";

    /** Descriptor key carrying the strategy arguments. */
    public static final String STRATEGY_ARGS_KEY = "strategy_args";

    /** Descriptor key carrying the custom executor class name ({@code CUSTOM} only). */
    public static final String STRATEGY_EXECUTOR_CLASS_KEY = "strategy_executor_class";

    /** Descriptor argument naming the candidate the router lands on when the strategy abstains. */
    public static final String DEFAULT_MODEL_KEY = "default_model";

    /** Descriptor argument mapping candidate names to their {@code describe(...)} descriptions. */
    public static final String CANDIDATE_DESCRIPTIONS_KEY = "candidate_descriptions";

    /** Descriptor argument switching fallback on; see {@link Builder#fallback(boolean)}. */
    public static final String FALLBACK_KEY = "fallback";

    private ModelRouter() {}

    /**
     * Start building a router over the given candidate model names (order matters for fallback).
     */
    public static Builder of(String... candidates) {
        return new Builder(Arrays.asList(candidates));
    }

    /** Fluent builder that produces the {@link ResourceDescriptor} of a model router. */
    public static final class Builder {
        private final List<String> candidates;
        private final Map<String, String> descriptions = new HashMap<>();
        private RoutingStrategy strategy;
        private String defaultModel;
        private boolean fallback = false;
        private Escalation escalation;

        private Builder(List<String> candidates) {
            this.candidates = candidates;
        }

        public Builder strategy(RoutingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * Attach a human-readable description to a candidate, surfaced to strategies via {@link
         * RoutingCandidate#getDescription()}. Descriptions are how semantic strategies — including
         * the framework-managed LLM judge — learn what each candidate is for, so declare them here
         * (once, on the router) rather than in per-strategy arguments.
         */
        public Builder describe(String candidate, String description) {
            if (!candidates.contains(candidate)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Cannot describe '%s': not one of the candidates %s.",
                                candidate, candidates));
            }
            descriptions.put(candidate, description);
            return this;
        }

        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * Whether to try remaining candidates (in declaration order) after the selected model has
         * exhausted its own retry policy. Applies to the initial routed request only; tool-call
         * rounds keep the already-selected model for conversation coherence. Fallback outcomes are
         * recorded on the response ({@code model_routing} extra args) and as a second {@code
         * ModelRoutingEvent} with source {@code fallback}.
         */
        public Builder fallback(boolean fallback) {
            this.fallback = fallback;
            return this;
        }

        /**
         * Escalate answers that arrive but fail a quality check to the next of the declared tiers;
         * see {@link Escalation}. Fallback keeps handling invocation failures. With tiers set,
         * {@link #strategy(RoutingStrategy)} may be omitted: every request starts on the first
         * tier, which is also the default model unless one is declared.
         */
        public Builder escalation(Escalation escalation) {
            this.escalation = escalation;
            return this;
        }

        /**
         * Builds the descriptor to register after checking the declaration.
         *
         * @throws IllegalStateException if no strategy was set
         * @throws IllegalArgumentException if the candidate list is empty, a candidate name is
         *     empty or duplicated, the default model is not a candidate, or a rule key is not a
         *     candidate
         */
        public ResourceDescriptor build() {
            if (strategy == null && escalation == null) {
                throw new IllegalStateException(
                        "ModelRouter requires a strategy(...) or escalation(...) tiers.");
            }
            if (escalation != null) {
                escalation.requireTiersAreCandidates("ModelRouter", candidates);
            }
            // A duplicate or blank candidate, or a default model that is not a candidate, fails at
            // the registration call site; the plan applies the same rule to descriptors that did
            // not pass through here.
            RoutingCandidateValidator.validate("ModelRouter", candidates, defaultModel);
            if (strategy == null) {
                // Tiers without a strategy: every request starts on the first tier (see the
                // plan-side resource); the descriptor carries no strategy_type.
                Map<String, Object> args = new HashMap<>();
                args.put(CANDIDATES_KEY, new ArrayList<>(candidates));
                if (!descriptions.isEmpty()) {
                    args.put(CANDIDATE_DESCRIPTIONS_KEY, new HashMap<>(descriptions));
                }
                args.put(
                        DEFAULT_MODEL_KEY,
                        defaultModel != null ? defaultModel : escalation.getTiers().get(0));
                args.put(FALLBACK_KEY, fallback);
                escalation.toArguments(args);
                return new ResourceDescriptor(ResourceName.ModelRouter.MODEL_ROUTER, args);
            }
            // Rule shape/pattern validation ran in the RoutingStrategy constructor (the fluent
            // declaration path). build() additionally checks rule keys against the candidate set
            // so a typo fails at the registration call site; descriptors that skip the builder get
            // the same check at plan construction (AgentPlan#validateRuleKeys, with a
            // router-scoped message).
            if (strategy.getType() == RoutingStrategyType.RULE_BASED) {
                Object rules = strategy.getArguments().get(RoutingStrategy.ARG_RULES);
                if (rules instanceof Map) {
                    for (Object ruleKey : ((Map<?, ?>) rules).keySet()) {
                        if (!candidates.contains(ruleKey)) {
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Routing rule key '%s' is not one of the candidates"
                                                    + " %s.",
                                            ruleKey, candidates));
                        }
                    }
                }
            }
            RoutingCandidateValidator.requireDescriptionsForDecisionStrategy(
                    "ModelRouter", strategy, candidates, descriptions);
            Map<String, Object> args = new HashMap<>();
            args.put(CANDIDATES_KEY, new ArrayList<>(candidates));
            if (!descriptions.isEmpty()) {
                args.put(CANDIDATE_DESCRIPTIONS_KEY, new HashMap<>(descriptions));
            }
            if (defaultModel != null) {
                args.put(DEFAULT_MODEL_KEY, defaultModel);
            }
            args.put(FALLBACK_KEY, fallback);
            args.put(STRATEGY_TYPE_KEY, strategy.getType().tag());
            args.put(STRATEGY_ARGS_KEY, strategy.getArguments());
            if (strategy.getExecutorClass() != null) {
                args.put(STRATEGY_EXECUTOR_CLASS_KEY, strategy.getExecutorClass());
            }
            if (escalation != null) {
                escalation.toArguments(args);
            }
            return new ResourceDescriptor(ResourceName.ModelRouter.MODEL_ROUTER, args);
        }
    }
}
