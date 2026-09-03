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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.context.RunnerContext;

/**
 * The plan-side execution contract for a declared {@link RoutingStrategy}: one implementation per
 * {@link org.apache.flink.agents.api.chat.model.routing.RoutingStrategyType}, resolved by {@link
 * RoutingExecutors}. {@link ModelRoutingResolver} owns everything the executors share — the single
 * durable {@code "route:<router>"} persistence boundary, decision normalization, and the
 * observability event — so an executor only turns a declaration plus request context into a {@link
 * RoutingDecision}.
 *
 * <p>Durable sequencing contract: the engine's durable substrate replays a <b>flat, order-matched
 * call sequence</b> per action — durable calls cannot nest (a nested call would persist before its
 * enclosing call, but replay consults the enclosing call first, clearing the recovery state). An
 * executor that needs durable sub-calls of its own (the judge's chat call) therefore declares
 * {@link #issuesDurableCalls()} and is invoked <i>before</i> the resolver's persistence call, so
 * its records land as flat siblings ahead of the decision record; on replay the executor re-runs
 * cheaply against its replayed sub-call records, and the replayed decision wins. Executors without
 * durable sub-calls run <i>inside</i> the persistence boundary and are never re-invoked on replay.
 */
interface RoutingExecutor {

    /**
     * Whether this executor issues its own flat durable calls via the {@link RunnerContext} (see
     * the class contract). Pure executors keep the default.
     */
    default boolean issuesDurableCalls() {
        return false;
    }

    /**
     * The {@link org.apache.flink.agents.api.event.ModelRoutingEvent} decision source recorded for
     * a concrete (non-abstain) decision from this executor.
     */
    String decisionSource();

    /**
     * Executes the declared strategy for one request. An abstain decision resolves to the router's
     * default model; a thrown exception follows the request's error-handling strategy.
     */
    RoutingDecision route(RoutingStrategy strategy, RoutingContext context, RunnerContext ctx)
            throws Exception;
}
