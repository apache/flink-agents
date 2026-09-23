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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a model router's candidate declaration: at least one candidate, every name a non-empty
 * String, no duplicates, and a default model (when set) that is one of them.
 *
 * <p>This is the single check behind every declaration path: {@link ModelRouter.Builder#build()} at
 * the registration call site, plan construction for descriptors that never went through the
 * builder, and the {@link ModelRouter} constructor itself. A typo therefore fails with one message
 * wherever it is caught, rather than per routed request inside the durable call on the TaskManager.
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
}
