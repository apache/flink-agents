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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A framework resource that <b>selects</b> a concrete chat model for a request. It does not call
 * the backend itself — {@code ChatModelAction} resolves the router, runs its {@link
 * RoutingStrategy} to get a {@link RoutingDecision}, and then runs the normal chat path against the
 * chosen model.
 *
 * <p>Built with the fluent {@link #of(String...)} builder, which produces a {@link
 * ResourceDescriptor} the framework instantiates reflectively. The strategy is carried by class
 * name + args (see {@link RoutingStrategyDescriptor}) so it is plan-serializable.
 *
 * <p>Abstain ({@link RoutingDecision#abstain()}) → {@link #getDefaultModel()}. A returned name that
 * is not a candidate is an invalid decision and is failed clearly by the caller.
 */
public class ModelRouter extends Resource {

    private final List<RoutingCandidate> candidates;
    private final String defaultModel;
    private final boolean fallbackEnabled;
    private final RoutingStrategy strategy;

    public ModelRouter(ResourceDescriptor descriptor, ResourceContext resourceContext)
            throws Exception {
        super(descriptor, resourceContext);
        List<String> names = descriptor.getArgument("candidates");
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("ModelRouter requires at least one candidate.");
        }
        Map<String, String> descriptions =
                descriptor.getArgument("candidate_descriptions", Collections.emptyMap());
        List<RoutingCandidate> parsed = new ArrayList<>();
        Set<String> uniqueNames = new LinkedHashSet<>();
        for (String name : names) {
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException(
                        String.format("ModelRouter candidate '%s' is duplicated.", name));
            }
            parsed.add(new RoutingCandidate(name, descriptions.get(name)));
        }
        this.candidates = Collections.unmodifiableList(parsed);
        this.defaultModel = descriptor.getArgument("default_model");
        if (this.defaultModel != null && !isCandidate(this.defaultModel)) {
            throw new IllegalArgumentException(
                    String.format(
                            "ModelRouter default model '%s' is not one of the candidates %s.",
                            this.defaultModel, getCandidateNames()));
        }
        this.fallbackEnabled =
                Boolean.TRUE.equals(descriptor.getArgument("fallback", Boolean.FALSE));
        String strategyClazz = descriptor.getArgument(STRATEGY_CLAZZ_KEY);
        Map<String, Object> strategyArgs =
                descriptor.getArgument(STRATEGY_ARGS_KEY, Collections.emptyMap());
        this.strategy = instantiateStrategy(strategyClazz, strategyArgs);
    }

    /** Descriptor key carrying the strategy class name. */
    public static final String STRATEGY_CLAZZ_KEY = "strategy_clazz";

    /** Descriptor key carrying the strategy construction arguments. */
    public static final String STRATEGY_ARGS_KEY = "strategy_args";

    /**
     * The strategy instantiation contract (Map-arg constructor, then no-arg, via the thread context
     * classloader). Public so plan-time validation constructs strategies exactly as the runtime
     * does — divergence here is what turns validation into a no-op.
     */
    @SuppressWarnings("unchecked")
    public static RoutingStrategy instantiateStrategy(String clazz, Map<String, Object> args)
            throws Exception {
        if (clazz == null || clazz.isEmpty()) {
            throw new IllegalArgumentException("ModelRouter requires a routing strategy.");
        }
        Class<?> c = Class.forName(clazz, true, Thread.currentThread().getContextClassLoader());
        try {
            Constructor<?> ctor = c.getConstructor(Map.class);
            return (RoutingStrategy) ctor.newInstance(args);
        } catch (NoSuchMethodException noMapCtor) {
            return (RoutingStrategy) c.getConstructor().newInstance();
        }
    }

    /** Run the strategy for the given context. */
    public RoutingDecision route(RoutingContext context) throws Exception {
        return strategy.route(context);
    }

    /**
     * The configured strategy instance. Exposed so the engine can detect framework-managed
     * strategies (e.g. {@link LlmJudgeRoutingStrategy}) and execute them on its own paths.
     */
    public RoutingStrategy getStrategy() {
        return strategy;
    }

    public List<RoutingCandidate> getCandidates() {
        return candidates;
    }

    public List<String> getCandidateNames() {
        List<String> names = new ArrayList<>();
        for (RoutingCandidate candidate : candidates) {
            names.add(candidate.getName());
        }
        return names;
    }

    public Optional<String> getDefaultModel() {
        return Optional.ofNullable(defaultModel);
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    /** Whether the given model name is one of this router's candidates. */
    public boolean isCandidate(String model) {
        for (RoutingCandidate candidate : candidates) {
            if (candidate.getName().equals(model)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.MODEL_ROUTER;
    }

    /**
     * Start building a router over the given candidate model names (order matters for fallback).
     */
    public static Builder of(String... candidates) {
        return new Builder(Arrays.asList(candidates));
    }

    /** Fluent builder that produces a {@link ResourceDescriptor} for a {@link ModelRouter}. */
    public static final class Builder {
        private final List<String> candidates;
        private final Map<String, String> descriptions = new HashMap<>();
        private RoutingStrategyDescriptor strategy;
        private String defaultModel;
        private boolean fallback = false;

        private Builder(List<String> candidates) {
            this.candidates = candidates;
        }

        public Builder strategy(RoutingStrategyDescriptor strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * Attach a human-readable description to a candidate, surfaced to strategies via {@link
         * RoutingCandidate#getDescription()}. Descriptions are how semantic strategies — and future
         * framework-managed LLM routing — learn what each candidate is for, so declare them here
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

        public ResourceDescriptor build() {
            if (strategy == null) {
                throw new IllegalStateException("ModelRouter requires a strategy(...).");
            }
            // Rule keys are candidate names and rule values are regex patterns; validate both
            // here, where the full map is in hand, so a typo fails at the registration call site
            // instead of throwing per record at runtime (an invalid pattern is never cached by
            // the resource cache, so it would otherwise re-throw on every routed request).
            if (RuleBasedRoutingStrategy.class.getName().equals(strategy.getClazz())) {
                Object rules = strategy.getArguments().get("rules");
                if (rules instanceof Map) {
                    for (Map.Entry<?, ?> rule : ((Map<?, ?>) rules).entrySet()) {
                        if (!candidates.contains(String.valueOf(rule.getKey()))) {
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Routing rule key '%s' is not one of the candidates %s.",
                                            rule.getKey(), candidates));
                        }
                        if (!(rule.getValue() instanceof String)) {
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Routing rule pattern for candidate '%s' must be a non-null String, got %s.",
                                            rule.getKey(),
                                            rule.getValue() == null
                                                    ? "null"
                                                    : rule.getValue().getClass().getSimpleName()));
                        }
                        try {
                            Pattern.compile((String) rule.getValue());
                        } catch (PatternSyntaxException e) {
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Routing rule pattern '%s' for candidate '%s' is not a valid regex.",
                                            rule.getValue(), rule.getKey()),
                                    e);
                        }
                    }
                }
            }
            // The judge model is resolved at request time (resources may be registered in any
            // order), but a structurally invalid configuration should still fail at build().
            if (LlmJudgeRoutingStrategy.class.getName().equals(strategy.getClazz())) {
                // Single source of truth: the strategy constructor owns its argument rules;
                // constructing (and discarding) one here surfaces them at the call site.
                new LlmJudgeRoutingStrategy(strategy.getArguments());
            }
            Map<String, Object> args = new HashMap<>();
            args.put("candidates", new ArrayList<>(candidates));
            if (!descriptions.isEmpty()) {
                args.put("candidate_descriptions", new HashMap<>(descriptions));
            }
            if (defaultModel != null) {
                args.put("default_model", defaultModel);
            }
            args.put("fallback", fallback);
            args.put(STRATEGY_CLAZZ_KEY, strategy.getClazz());
            args.put(STRATEGY_ARGS_KEY, strategy.getArguments());
            return new ResourceDescriptor(ModelRouter.class.getName(), args);
        }
    }
}
