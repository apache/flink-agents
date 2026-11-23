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
import org.apache.flink.agents.api.chat.model.routing.RoutingCandidate;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategyType;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.CANDIDATES_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.CANDIDATE_DESCRIPTIONS_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.DEFAULT_MODEL_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.FALLBACK_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.STRATEGY_ARGS_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.STRATEGY_EXECUTOR_CLASS_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.STRATEGY_TYPE_KEY;

/**
 * The {@code MODEL_ROUTER} resource: a router declaration built with {@code ModelRouter.of(...)} in
 * the api module, decoded for the engine. It selects a concrete chat model for a request but
 * neither calls the backend nor runs the routing logic itself: {@link ModelRoutingResolver} asks
 * the strategy for the first model, and {@link EscalationEvaluator} applies the escalation policy
 * after each model round.
 *
 * <p>The constructor only decodes the descriptor. The declaration rules are applied when the agent
 * plan is built ({@code AgentPlan#validateRoutingStrategies}), the one point every construction
 * path passes — the Java builder, a YAML declaration, a descriptor built by hand, a plan restored
 * from JSON — so each meets the same checks before a request reaches this class. The builder and
 * the YAML {@code router} alias put a placeholder where the descriptor names its class ({@link
 * org.apache.flink.agents.api.resource.ResourceName.ModelRouter#MODEL_ROUTER}); the plan names this
 * class before it creates a provider, from a declaration or from a restored plan ({@link
 * #resourceDescriptor}).
 */
public class ModelRouter extends Resource {

    private final List<RoutingCandidate> candidates;
    @Nullable private final String defaultModel;
    private final boolean fallbackEnabled;
    private final RoutingStrategy strategy;
    @Nullable private final Escalation escalation;

    /**
     * The descriptor this resource is provided from: {@code declaration} with this class named. The
     * router resource is framework-owned, so a declaration does not choose the class it is provided
     * as. The api builder and the YAML {@code router} alias put a placeholder there, as a Java MCP
     * server declaration does, and whatever class a Java descriptor names is replaced. A descriptor
     * that names the api declaration class, as descriptors did before the declaration and the
     * resource were two classes, is provided as this resource like any other. A descriptor of
     * another language is not this resource's to name and is returned as it is.
     */
    public static ResourceDescriptor resourceDescriptor(ResourceDescriptor declaration) {
        String resource = ModelRouter.class.getName();
        if (!"java".equals(declaration.getLanguage()) || resource.equals(declaration.getClazz())) {
            return declaration;
        }
        return new ResourceDescriptor(
                declaration.getModule(), resource, declaration.getInitialArguments());
    }

    public ModelRouter(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        List<String> names = descriptor.getArgument(CANDIDATES_KEY);
        Map<?, ?> descriptions =
                descriptor.getArgument(CANDIDATE_DESCRIPTIONS_KEY, Collections.emptyMap());
        List<RoutingCandidate> decoded = new ArrayList<>();
        for (String name : names) {
            decoded.add(new RoutingCandidate(name, (String) descriptions.get(name)));
        }
        this.candidates = Collections.unmodifiableList(decoded);
        this.escalation =
                Escalation.fromArguments("ModelRouter", descriptor.getInitialArguments())
                        .orElse(null);
        String typeTag = descriptor.getArgument(STRATEGY_TYPE_KEY);
        if (typeTag == null || typeTag.isEmpty()) {
            // Tiers without a strategy: an empty rule set abstains on every request, and the
            // default model (the first tier unless declared) starts it.
            this.strategy = Strategies.rules(Collections.emptyMap());
        } else {
            this.strategy =
                    RoutingStrategy.parse(
                            RoutingStrategyType.fromTag(typeTag),
                            descriptor.getArgument(STRATEGY_ARGS_KEY, Collections.emptyMap()),
                            descriptor.getArgument(STRATEGY_EXECUTOR_CLASS_KEY));
        }
        String declaredDefault = descriptor.getArgument(DEFAULT_MODEL_KEY);
        this.defaultModel =
                declaredDefault == null && this.escalation != null
                        ? this.escalation.getTiers().get(0)
                        : declaredDefault;
        this.fallbackEnabled =
                Boolean.TRUE.equals(descriptor.getArgument(FALLBACK_KEY, Boolean.FALSE));
    }

    /** The configured strategy declaration (type + arguments). */
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

    /** The candidate that answers when the strategy abstains. */
    public Optional<String> getDefaultModel() {
        return Optional.ofNullable(defaultModel);
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    /** The quality-triggered escalation policy, when one is declared. */
    public Optional<Escalation> getEscalation() {
        return Optional.ofNullable(escalation);
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
}
