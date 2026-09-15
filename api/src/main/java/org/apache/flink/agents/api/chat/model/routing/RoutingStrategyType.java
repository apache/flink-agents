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

/**
 * The language-neutral identity of a {@link RoutingStrategy}. Serialized into the agent plan as a
 * lower-case tag (e.g. {@code "llm_judge"}), so a non-Java runtime can dispatch to its own executor
 * for the same strategy without referencing Java class names.
 */
public enum RoutingStrategyType {

    /** Built-in keyword/regex rules evaluated against the newest user message. */
    RULE_BASED,

    /**
     * Framework-managed LLM-as-judge: the engine executes the judge chat call on its durable,
     * metered, observable path and derives the decision from the verdict.
     */
    LLM_JUDGE,

    /** A user-provided {@link CustomRoutingExecutor} referenced by class name. */
    CUSTOM;

    /** The plan-serialized form of this type ({@code rule_based}, {@code llm_judge}, ...). */
    public String tag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parses the plan-serialized form back into a type. */
    public static RoutingStrategyType fromTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("Routing strategy type must be non-empty.");
        }
        return RoutingStrategyType.valueOf(tag.toUpperCase(java.util.Locale.ROOT));
    }
}
