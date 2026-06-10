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

package org.apache.flink.agents.runtime.condition;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.condition.ParsedCondition;
import org.apache.flink.agents.plan.condition.ParsedCondition.CelExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Routes an event to matching actions: type-index fast path first, then CEL slow path.
 *
 * <p>Each action fires at most once per event; typed hits ordered before CEL hits.
 */
public final class ActionRouter {

    private final AgentPlan agentPlan;

    /** Null when the plan contains no CEL expressions. */
    private CelConditionEvaluator conditionEvaluator;

    public ActionRouter(AgentPlan agentPlan) {
        if (agentPlan == null) {
            throw new IllegalArgumentException("ActionRouter: agentPlan must not be null");
        }
        this.agentPlan = agentPlan;
    }

    /** Pre-compiles all CEL expressions in the plan. */
    public void open() {
        List<CelExpression> celExpressions = new ArrayList<>();
        for (Action action : agentPlan.getActions().values()) {
            for (ParsedCondition pc : action.getParsedConditions()) {
                if (pc instanceof CelExpression) {
                    celExpressions.add((CelExpression) pc);
                }
            }
        }
        if (celExpressions.isEmpty()) {
            return;
        }
        conditionEvaluator =
                new CelConditionEvaluator(
                        agentPlan
                                .getConfig()
                                .get(AgentConfigOptions.CEL_EVALUATION_FAILURE_POLICY));
        conditionEvaluator.initPrograms(celExpressions);
    }

    /** Returns actions to fire for {@code event}: typed hits first, then CEL hits. */
    public List<Action> route(Event event) {
        List<Action> typedHits =
                agentPlan
                        .getActionsByEvent()
                        .getOrDefault(event.getType(), Collections.emptyList());

        // CEL candidates = actions with at least one CEL entry, excluding those already
        // matched by typed routing for this event type. This avoids double-firing.
        List<Action> celCandidates;
        List<Action> withCel = agentPlan.getActionsWithCel();
        if (withCel.isEmpty()) {
            celCandidates = Collections.emptyList();
        } else {
            celCandidates = new ArrayList<>();
            for (Action a : withCel) {
                if (!typedHits.contains(a)) {
                    celCandidates.add(a);
                }
            }
        }

        if (celCandidates.isEmpty()) {
            return typedHits;
        }

        // Preserves typed-first ordering and deduplicates.
        LinkedHashSet<Action> matched = new LinkedHashSet<>(typedHits);

        Map<String, Object> activation = null;
        for (Action a : celCandidates) {
            // Within-action OR: first matching CEL expression admits the action.
            for (ParsedCondition pc : a.getParsedConditions()) {
                if (!(pc instanceof CelExpression)) {
                    continue;
                }
                if (activation == null) {
                    activation = conditionEvaluator.createActivation(event);
                }
                if (conditionEvaluator.evaluate((CelExpression) pc, activation)) {
                    matched.add(a);
                    break;
                }
            }
        }
        return new ArrayList<>(matched);
    }

    /** Idempotent. */
    public void close() {
        if (conditionEvaluator != null) {
            conditionEvaluator.close();
            conditionEvaluator = null;
        }
    }
}
