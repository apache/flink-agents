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
import java.util.Objects;

/**
 * User intent about how an output schema should be applied to a chat request.
 *
 * <p>This expresses <b>policy</b> only. Whether a connection can carry a schema natively, and
 * whether its effective model is known to honor it, is answered per request by {@link
 * BaseChatModelConnection#supportsNativeStructuredOutput(Object, java.util.List, java.util.Map)}.
 * {@link #resolvesToNative(NativeStructuredOutputSupport)} combines the two.
 */
public enum StructuredOutputStrategy {
    /**
     * Use the provider's native structured-output API when the effective model is known to honor
     * it, and fall back to prompt engineering otherwise. This is the default.
     */
    AUTO,

    /**
     * Use the provider's native structured-output API whenever the request can carry the schema,
     * regardless of whether the effective model is known to honor it.
     */
    NATIVE,

    /**
     * Never use the provider's native structured-output API; rely on prompt engineering alone. This
     * matches the behavior of connections that have no native translation.
     */
    PROMPT;

    /**
     * Resolves this policy against a connection's support for a request into whether the provider's
     * native structured-output API should be used.
     *
     * <ul>
     *   <li>{@code AUTO} resolves to native only on {@link
     *       NativeStructuredOutputSupport#NATIVE_RECOMMENDED}, and to the prompt-engineering
     *       fallback otherwise.
     *   <li>{@code NATIVE} resolves to native whenever the request can carry the schema, so an
     *       explicit user intent surfaces a provider error rather than silently degrading. On
     *       {@link NativeStructuredOutputSupport#INFEASIBLE} it throws, because there is no native
     *       request to send and degrading would contradict the intent.
     *   <li>{@code PROMPT} never resolves to native.
     * </ul>
     *
     * @param support the connection's answer for the request
     * @return true if native structured output should be applied
     * @throws IllegalArgumentException if this is {@code NATIVE} and {@code support} is {@code
     *     INFEASIBLE}
     * @throws NullPointerException if {@code support} is null
     */
    public boolean resolvesToNative(NativeStructuredOutputSupport support) {
        Objects.requireNonNull(support, "support");
        switch (this) {
            case NATIVE:
                if (support == NativeStructuredOutputSupport.INFEASIBLE) {
                    throw new IllegalArgumentException(
                            "Structured output strategy NATIVE requires native structured output, but the connection cannot apply the schema to this request.");
                }
                return true;
            case PROMPT:
                return false;
            case AUTO:
            default:
                return support == NativeStructuredOutputSupport.NATIVE_RECOMMENDED;
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
