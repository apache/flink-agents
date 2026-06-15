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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pluggable LLM router that selects, per request, which underlying chat model should serve it.
 *
 * <p>The router is a drop-in {@link BaseChatModelSetup}: it reports {@link ResourceType#CHAT_MODEL}
 * and is resolved by the built-in chat action exactly like any other model, so an agent points at
 * the router by name and nothing else in the framework needs to change. Concerns are layered:
 * selection ({@link RoutingStrategy}) decides the model, optional {@link CachingStrategy} memoizes
 * that decision per conversation, and {@link FallbackPolicy} decides what to try if the chosen
 * model errors. The router then delegates to the chosen model's own {@code chat(...)}, preserving
 * its prompt, tools, parameters, and token metrics.
 *
 * <h2>Configuration ({@link ResourceDescriptor} arguments)</h2>
 *
 * <ul>
 *   <li>{@code candidates} (required) — routable models; each a {@link RoutingCandidate}, a {@link
 *       String} name, or a {@link Map} with {@code name}/{@code description}/{@code metadata}.
 *       Every name must reference a registered {@code CHAT_MODEL} resource.
 *   <li>{@code strategy} (required) — a {@link ResourceDescriptor} naming the {@link
 *       RoutingStrategy} implementation and its args (use {@link
 *       org.apache.flink.agents.api.resource.ResourceName.RoutingStrategy} for built-ins). The
 *       class needs a public {@code (ResourceDescriptor, ResourceContext)} constructor (see {@link
 *       AbstractRoutingStrategy}); it is loaded with the thread context classloader so user classes
 *       resolve on a cluster.
 *   <li>{@code fallback} (optional, default {@code false}) — when {@code true}, a failing model
 *       falls back to the remaining candidates in declaration order.
 *   <li>{@code cache} (optional, default {@code true}) — memoize the selection per conversation.
 *   <li>{@code cache_size} (optional, default {@link CachingStrategy#DEFAULT_MAX_ENTRIES}) — LRU
 *       capacity when caching is enabled.
 *   <li>{@code default} (optional) — the candidate used when the strategy abstains or names a
 *       non-candidate (a routing miss). Must be one of {@code candidates}; defaults to the first
 *       candidate.
 * </ul>
 *
 * <p><b>Graceful degrade:</b> if the strategy returns {@code null} ("no opinion", e.g. a transient
 * LLM-judge failure) or a name that is not a configured candidate, the router treats it as a
 * routing miss and serves the request from {@code default} (then the fallback order) rather than
 * failing.
 *
 * <p><b>Bash/skill tool args (v1 scope):</b> the built-in chat action injects bash allowlists and
 * skill directories from the resource resolved by the agent's model name — i.e. this router — not
 * the chosen backend. So configure {@code allowed_commands} / {@code allowed_script_dirs} / {@code
 * skills} <b>on the router</b>; per-candidate skills/allowlists are not supported in v1.
 *
 * <p><b>Metrics note (v1):</b> retry metrics recorded by the built-in chat action are grouped under
 * this router's connection label ({@code "router"}), not the backend model actually used.
 * Per-backend attribution is a documented follow-up.
 */
public class ChatModelRouter extends BaseChatModelSetup {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelRouter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String ARG_CANDIDATES = "candidates";
    public static final String ARG_STRATEGY = "strategy";
    public static final String ARG_FALLBACK = "fallback";
    public static final String ARG_CACHE = "cache";
    public static final String ARG_CACHE_SIZE = "cache_size";
    public static final String ARG_DEFAULT = "default";

    private final List<RoutingCandidate> candidates;
    private final Set<String> candidateNames;
    private final RoutingStrategy strategy;
    private final FallbackPolicy fallbackPolicy;
    private final boolean fallbackEnabled;
    private final String defaultCandidate;

    @SuppressWarnings("unchecked")
    public ChatModelRouter(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        List<Object> rawCandidates = descriptor.getArgument(ARG_CANDIDATES);
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "ChatModelRouter requires a non-empty 'candidates' argument.");
        }
        List<RoutingCandidate> parsed = new ArrayList<>(rawCandidates.size());
        Set<String> names = new LinkedHashSet<>();
        for (Object spec : rawCandidates) {
            RoutingCandidate candidate = RoutingCandidate.from(spec);
            parsed.add(candidate);
            names.add(candidate.getName());
        }
        this.candidates = Collections.unmodifiableList(parsed);
        this.candidateNames = Collections.unmodifiableSet(names);

        ResourceDescriptor strategyDescriptor =
                toResourceDescriptor(descriptor.getArgument(ARG_STRATEGY));
        RoutingStrategy base = instantiateStrategy(strategyDescriptor, resourceContext);

        boolean cache = descriptor.getArgument(ARG_CACHE, Boolean.TRUE);
        if (cache) {
            int cacheSize =
                    descriptor.getArgument(ARG_CACHE_SIZE, CachingStrategy.DEFAULT_MAX_ENTRIES);
            this.strategy = new CachingStrategy(base, cacheSize);
        } else {
            this.strategy = base;
        }

        this.fallbackEnabled = descriptor.getArgument(ARG_FALLBACK, Boolean.FALSE);
        this.fallbackPolicy =
                fallbackEnabled ? FallbackPolicy.remainingCandidates() : FallbackPolicy.none();

        // Default candidate used on a routing miss (strategy abstains / names a non-candidate).
        // Validated at construction so a typo is a clear config error, not a per-request failure.
        String configuredDefault = descriptor.getArgument(ARG_DEFAULT);
        if (configuredDefault != null && !candidateNames.contains(configuredDefault)) {
            throw new IllegalArgumentException(
                    "ChatModelRouter 'default' '"
                            + configuredDefault
                            + "' is not one of the configured candidates "
                            + candidateNames);
        }
        this.defaultCandidate =
                configuredDefault != null ? configuredDefault : candidates.get(0).getName();
    }

    /**
     * The router has no connection of its own to resolve (it delegates to candidate models, each of
     * which resolves its own). Override to skip the base class's connection resolution.
     */
    @Override
    public void open() {
        // no-op
    }

    /**
     * Coerce the {@code strategy} argument into a {@link ResourceDescriptor}: a descriptor directly
     * (in-memory construction) or its deserialized {@link Map} form after the {@code AgentPlan}
     * round-trips through JSON. The {@link Map} form is converted with the canonical {@link
     * ObjectMapper} via {@link ResourceDescriptor}'s own Jackson binding, rather than hand-reading
     * field names.
     */
    private static ResourceDescriptor toResourceDescriptor(Object strategyArg) {
        if (strategyArg instanceof ResourceDescriptor) {
            return (ResourceDescriptor) strategyArg;
        }
        if (strategyArg instanceof Map) {
            return MAPPER.convertValue(strategyArg, ResourceDescriptor.class);
        }
        throw new IllegalArgumentException(
                "ChatModelRouter requires a 'strategy' argument of type ResourceDescriptor (or its"
                        + " serialized map form), but got: "
                        + (strategyArg == null ? "null" : strategyArg.getClass().getName()));
    }

    private static RoutingStrategy instantiateStrategy(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        String clazz = descriptor.getClazz();
        if (clazz == null || clazz.isEmpty()) {
            throw new IllegalArgumentException("Routing strategy descriptor is missing a class.");
        }
        try {
            // Use the thread context classloader (the convention in JavaResourceProvider) so that
            // user-supplied strategy classes resolve on a Flink cluster, not just the API jar.
            Class<?> strategyClass =
                    Class.forName(clazz, true, Thread.currentThread().getContextClassLoader());
            if (!RoutingStrategy.class.isAssignableFrom(strategyClass)) {
                throw new IllegalArgumentException(
                        clazz + " does not implement " + RoutingStrategy.class.getName());
            }
            Constructor<?> constructor =
                    strategyClass.getConstructor(ResourceDescriptor.class, ResourceContext.class);
            return (RoutingStrategy) constructor.newInstance(descriptor, resourceContext);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate routing strategy " + clazz, e);
        }
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            Map<String, Object> modelParams) {
        String primary = select(messages, promptArgs);
        if (primary == null) {
            // Routing miss (strategy abstained or chose a non-candidate): degrade to the default
            // candidate instead of failing, then apply the fallback order.
            primary = defaultCandidate;
        }
        List<String> order = fallbackPolicy.attemptOrder(primary, candidates);

        Exception lastError = null;
        for (String name : order) {
            try {
                return resolveCandidate(name).chat(messages, promptArgs, modelParams);
            } catch (Exception e) {
                lastError = e;
                if (!fallbackEnabled) {
                    throw asRuntime(e, name);
                }
                LOG.warn(
                        "Routed model '{}' failed; falling back to the next candidate. Cause: {}",
                        name,
                        e.toString());
            }
        }
        throw new RuntimeException(
                "All routed candidates failed for router. Tried: " + order, lastError);
    }

    /** Run the strategy and validate its choice against the configured candidates. */
    private String select(List<ChatMessage> messages, Map<String, Object> promptArgs) {
        String primary;
        try {
            primary =
                    strategy.route(
                            new RoutingContext(messages, promptArgs, candidates, resourceContext));
        } catch (Exception e) {
            throw new RuntimeException("Routing strategy failed to select a model.", e);
        }
        if (primary == null || primary.isEmpty()) {
            // Strategy abstained ("no opinion") -> routing miss; caller degrades to the default.
            return null;
        }
        if (!candidateNames.contains(primary)) {
            // A typo'd/unknown name must not hard-fail the request; treat it as a miss so the
            // router can degrade gracefully to the default candidate.
            LOG.warn(
                    "Routing strategy chose '{}', not a configured candidate {}; treating as a"
                            + " routing miss (using default '{}').",
                    primary,
                    candidateNames,
                    defaultCandidate);
            return null;
        }
        return primary;
    }

    private BaseChatModelSetup resolveCandidate(String name) throws Exception {
        if (resourceContext == null) {
            throw new IllegalStateException(
                    "Router has no ResourceContext; cannot resolve candidate '" + name + "'.");
        }
        Object resource = resourceContext.getResource(name, ResourceType.CHAT_MODEL);
        if (!(resource instanceof BaseChatModelSetup)) {
            throw new IllegalStateException(
                    "Routed resource '"
                            + name
                            + "' is not a chat model setup (CHAT_MODEL): "
                            + (resource == null ? "null" : resource.getClass().getName()));
        }
        return (BaseChatModelSetup) resource;
    }

    private static RuntimeException asRuntime(Exception e, String name) {
        if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        }
        return new RuntimeException("Routed model '" + name + "' failed.", e);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    /**
     * The router has no connection of its own; return a stable label so retry-metric grouping in
     * the built-in chat action never sees a null connection name.
     */
    @Override
    public String getConnectionName() {
        return "router";
    }

    /** The candidate models this router may route to. */
    public List<RoutingCandidate> getCandidates() {
        return candidates;
    }

    /** Whether the router falls back to the next candidate when the chosen model errors. */
    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }
}
