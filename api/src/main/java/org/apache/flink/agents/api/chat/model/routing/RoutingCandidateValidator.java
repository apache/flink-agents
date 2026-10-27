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

import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a model router's candidate declaration: at least one candidate, every name a non-empty
 * String, no duplicates, and a default model (when set) that is one of them.
 *
 * <p>This is the single check behind every declaration path: {@link ModelRouter.Builder#build()} at
 * the registration call site, plan construction for descriptors that never went through the
 * builder, and the {@link ModelRouter} constructor itself. A typo therefore fails with one message
 * wherever it is caught, rather than per routed request on the TaskManager, when the router is
 * resolved.
 *
 * <p>Internal contract shared with the plan module; not a stable public API.
 */
@Internal
public final class RoutingCandidateValidator {

    private RoutingCandidateValidator() {}

    /**
     * Validate the candidate declaration.
     *
     * @param subject how to name the router in the message, e.g. {@code "ModelRouter"} or {@code
     *     "Model router 'name'"}
     * @param candidates the declared candidate names; the elements are checked, so a raw list from
     *     a deserialized descriptor may be passed
     * @param defaultModel the declared default model, or null for none
     * @throws IllegalArgumentException when the declaration is invalid
     */
    public static void validate(
            String subject, @Nullable List<?> candidates, @Nullable Object defaultModel) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(subject + " requires at least one candidate.");
        }
        Set<Object> uniqueNames = new LinkedHashSet<>();
        for (Object name : candidates) {
            if (!(name instanceof String) || ((String) name).isEmpty()) {
                throw new IllegalArgumentException(
                        String.format(
                                "%s candidate names must be non-empty strings, got %s.",
                                subject, name == null ? "null" : "'" + name + "'"));
            }
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException(
                        String.format("%s candidate '%s' is duplicated.", subject, name));
            }
        }
        if (defaultModel != null && !uniqueNames.contains(defaultModel)) {
            throw new IllegalArgumentException(
                    String.format(
                            "%s default model '%s' is not one of the candidates %s.",
                            subject, defaultModel, candidates));
        }
    }

    /**
     * A decision-model strategy asks one choice question over the candidates, so it needs at least
     * two of them, and it routes on their descriptions alone — they are the question's criteria —
     * so a candidate without one is invisible to the decision model. Applied by the router
     * constructor, by {@code ModelRouter.Builder#build()} and by plan validation, so every
     * declaration path fails with the same diagnostic; {@code subject} names the router.
     */
    /**
     * Every description declared for a candidate must be a String. Applied by the router
     * constructor and by plan validation, so a descriptor that skipped the builder fails with the
     * same diagnostic at plan construction rather than with a ClassCastException per routed
     * request; {@code subject} names the router. Entries for names that are not candidates are
     * ignored, as the constructor ignores them.
     */
    public static void requireStringDescriptions(
            String subject, List<String> candidates, Map<?, ?> descriptions) {
        for (String name : candidates) {
            Object description = descriptions.get(name);
            if (description != null && !(description instanceof String)) {
                throw new IllegalArgumentException(
                        String.format(
                                "%s candidate '%s' has a %s description; expected a String.",
                                subject, name, description.getClass().getSimpleName()));
            }
        }
    }

    public static void requireDescriptionsForDecisionStrategy(
            String subject,
            RoutingStrategy strategy,
            List<String> candidates,
            Map<?, ?> descriptions) {
        if (strategy.getType() != RoutingStrategyType.DECISION_MODEL) {
            return;
        }
        // A choice question needs something to choose between: decision providers reject a
        // single option, so a one-candidate router would fail every request on the TaskManager.
        if (candidates.size() < 2) {
            throw new IllegalArgumentException(
                    String.format(
                            "%s uses Strategies.decision, which requires at least two candidates"
                                    + " (got %s).",
                            subject, candidates));
        }
        List<String> missing = new ArrayList<>();
        for (String candidate : candidates) {
            Object description = descriptions == null ? null : descriptions.get(candidate);
            if (!(description instanceof String) || ((String) description).isBlank()) {
                missing.add(candidate);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format(
                            "%s uses Strategies.decision, which requires a description for every"
                                    + " candidate (describe(name, description)); missing for %s.",
                            subject, missing));
        }
    }
}
