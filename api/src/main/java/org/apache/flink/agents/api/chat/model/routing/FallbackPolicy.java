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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The <b>resilience</b> concern of a {@link ChatModelRouter}, kept separate from selection ({@link
 * RoutingStrategy}). Given the strategy's primary choice and the configured candidates, it produces
 * the ordered list of model names the router will try in turn until one succeeds.
 */
@FunctionalInterface
public interface FallbackPolicy {

    /**
     * Ordered, de-duplicated model names to attempt, primary first.
     *
     * @param primary the model chosen by the {@link RoutingStrategy}
     * @param candidates all configured candidates (for fallback ordering)
     */
    List<String> attemptOrder(String primary, List<RoutingCandidate> candidates);

    /** No fallback: only the chosen model is attempted; its failure surfaces to the caller. */
    static FallbackPolicy none() {
        return (primary, candidates) -> Collections.singletonList(primary);
    }

    /**
     * On failure, fall back to the remaining configured candidates in declaration order (primary
     * first, then the rest), de-duplicated.
     */
    static FallbackPolicy remainingCandidates() {
        return (primary, candidates) -> {
            LinkedHashSet<String> order = new LinkedHashSet<>();
            order.add(primary);
            for (RoutingCandidate candidate : candidates) {
                order.add(candidate.getName());
            }
            return new ArrayList<>(order);
        };
    }
}
