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

package org.apache.flink.agents.runtime.skill;

import org.apache.flink.agents.runtime.skill.repository.ClasspathSkillRepository;
import org.apache.flink.agents.runtime.skill.repository.FileSystemSkillRepository;
import org.apache.flink.agents.runtime.skill.repository.URLSkillRepository;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Scheme-keyed registry mapping a skill source scheme (e.g. {@code "local"}, {@code "url"}, {@code
 * "classpath"}) to a {@link SkillSourceHandler}. Built-ins are registered via the static
 * initializer; external code may add custom schemes via {@link #register}.
 *
 * <p>Adding a new source = one {@link #register} call plus one {@code SkillRepository}
 * implementation. {@code SkillManager} and {@code AgentPlan} need not change.
 */
public final class SkillSourceRegistry {

    private static final Map<String, SkillSourceHandler> HANDLERS = new ConcurrentHashMap<>();

    static {
        register(
                "local",
                (params, cl) -> new FileSystemSkillRepository(require(params, "local", "path")),
                params -> params.getOrDefault("path", ""));
        register(
                "url",
                (params, cl) -> new URLSkillRepository(require(params, "url", "url")),
                params -> params.getOrDefault("url", ""));
        register(
                "classpath",
                (params, cl) ->
                        new ClasspathSkillRepository(require(params, "classpath", "resource"), cl),
                params -> params.getOrDefault("resource", ""));
    }

    private SkillSourceRegistry() {}

    /**
     * Register a {@code handler} under {@code scheme}. The scheme is normalized to lowercase. An
     * existing handler for the same scheme is replaced. {@link
     * SkillSourceHandler#describeLocation(Map)} falls back to the default (raw {@code params}); use
     * {@link #register(String, SkillSourceHandler, Function)} to supply a tighter description.
     */
    public static void register(String scheme, SkillSourceHandler handler) {
        HANDLERS.put(scheme.toLowerCase(Locale.ROOT), handler);
    }

    /**
     * Register a {@code handler} under {@code scheme} together with a {@code describer} that
     * extracts the human-readable location from {@code params} (e.g. the {@code path} or {@code
     * url} value). The two functions are composed into one {@link SkillSourceHandler} so that
     * {@link SkillManager} can ask the handler "where did this come from?" without a parallel
     * scheme ladder.
     */
    public static void register(
            String scheme,
            SkillSourceHandler handler,
            Function<Map<String, String>, String> describer) {
        register(
                scheme,
                new SkillSourceHandler() {
                    @Override
                    public SkillRepository open(Map<String, String> params, ClassLoader cl)
                            throws java.io.IOException {
                        return handler.open(params, cl);
                    }

                    @Override
                    public String describeLocation(Map<String, String> params) {
                        return describer.apply(params);
                    }
                });
    }

    /**
     * Return the handler for {@code scheme}, or throw if unknown. Throwing message lists the
     * currently registered schemes to aid debugging cross-language plan mismatches.
     */
    public static SkillSourceHandler get(String scheme) {
        SkillSourceHandler handler = HANDLERS.get(scheme.toLowerCase(Locale.ROOT));
        if (handler == null) {
            throw new IllegalArgumentException(
                    "Unknown skill source scheme: "
                            + scheme
                            + ". Registered schemes: "
                            + new TreeSet<>(HANDLERS.keySet()));
        }
        return handler;
    }

    private static String require(Map<String, String> params, String scheme, String key) {
        String value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required param '"
                            + key
                            + "' for skill source scheme '"
                            + scheme
                            + "'. Got: "
                            + params);
        }
        return value;
    }
}
