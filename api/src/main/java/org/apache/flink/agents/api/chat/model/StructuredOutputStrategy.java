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

package org.apache.flink.agents.api.chat.model;

import java.util.Locale;

/**
 * User intent about how an output schema should be applied to a chat request.
 *
 * <p>This expresses <b>policy</b> only. Whether a connection <i>can</i> apply the provider's native
 * structured-output API is a separate, model-dependent <b>capability</b> question answered by
 * {@link BaseChatModelConnection#supportsNativeStructuredOutput(String)}. Policy and capability are
 * combined at request-build time.
 */
public enum StructuredOutputStrategy {
    /**
     * Use the provider's native structured-output API when the effective model is capable of it,
     * and fall back to prompt engineering otherwise. This is the default.
     */
    AUTO,

    /**
     * Always use the provider's native structured-output API, without consulting the capability
     * predicate.
     */
    NATIVE,

    /**
     * Never use the provider's native structured-output API; rely on prompt engineering alone. This
     * matches the behavior of connections that have no native translation.
     */
    PROMPT;

    /**
     * Resolves this policy against a connection's model-dependent capability into whether the
     * provider's native structured-output API should be used.
     *
     * <ul>
     *   <li>{@code AUTO} defers to {@code modelCapable}: native when the effective model can, else
     *       the prompt-engineering fallback.
     *   <li>{@code NATIVE} always resolves to native, ignoring {@code modelCapable}, so an explicit
     *       user intent surfaces a provider error rather than silently degrading.
     *   <li>{@code PROMPT} never resolves to native.
     * </ul>
     *
     * @param modelCapable whether the connection reports the effective model as natively capable
     * @return true if native structured output should be applied
     */
    public boolean resolvesToNative(boolean modelCapable) {
        switch (this) {
            case NATIVE:
                return true;
            case PROMPT:
                return false;
            case AUTO:
            default:
                return modelCapable;
        }
    }

    /**
     * Resolves a strategy from a descriptor argument, which may arrive either as a {@code
     * StructuredOutputStrategy} or — across the Python bridge, where arguments are carried as JSON
     * — as its case-insensitive name.
     *
     * @param value the raw descriptor argument, may be null
     * @param defaultValue the strategy to use when {@code value} is null
     * @return the resolved strategy
     * @throws IllegalArgumentException if {@code value} is neither null, a {@code
     *     StructuredOutputStrategy}, nor the name of one
     */
    public static StructuredOutputStrategy fromArgument(
            Object value, StructuredOutputStrategy defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StructuredOutputStrategy) {
            return (StructuredOutputStrategy) value;
        }
        if (value instanceof String) {
            try {
                return valueOf(((String) value).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        String.format(
                                "Unknown structured output strategy '%s'. Expected one of: AUTO, NATIVE, PROMPT.",
                                value),
                        e);
            }
        }
        throw new IllegalArgumentException(
                String.format(
                        "Unsupported structured output strategy type '%s'. Expected a StructuredOutputStrategy or its name.",
                        value.getClass().getName()));
    }
}
