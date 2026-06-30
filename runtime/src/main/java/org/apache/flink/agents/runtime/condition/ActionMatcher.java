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
import org.apache.flink.agents.plan.condition.ActionSelector;
import org.apache.flink.agents.plan.condition.ActionSelector.ConditionExpression;
import org.apache.flink.agents.plan.condition.ActionSelector.EventTypeMatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Matches actions against event types and condition expressions. */
public final class ActionMatcher {

    private final Map<String, Set<Action>> actionsByEventType;
    private final Map<Action, List<ConditionExpressionCompiler.CompiledCondition>>
            compiledConditionsByAction;
    private final ConditionEvaluator conditionEvaluator;

    public ActionMatcher(AgentPlan agentPlan) {
        if (agentPlan == null) {
            throw new IllegalArgumentException("ActionMatcher: agentPlan must not be null");
        }
        Map<String, Set<Action>> eventTypeIndex = new LinkedHashMap<>();
        Map<Action, List<ConditionExpressionCompiler.CompiledCondition>> conditionIndex =
                new LinkedHashMap<>();
        for (Action action : agentPlan.getActions().values()) {
            List<ConditionExpressionCompiler.CompiledCondition> actionConditions =
                    new ArrayList<>();
            for (ActionSelector selector : action.getSelectors()) {
                if (selector instanceof EventTypeMatch) {
                    String eventType = ((EventTypeMatch) selector).eventType();
                    // Raw selectors preserve duplicates; the runtime index keeps each action once.
                    eventTypeIndex
                            .computeIfAbsent(eventType, ignored -> new LinkedHashSet<>())
                            .add(action);
                    continue;
                }

                ConditionExpression condition = (ConditionExpression) selector;
                actionConditions.add(ConditionExpressionCompiler.compile(condition));
            }
            if (!actionConditions.isEmpty()) {
                conditionIndex.put(action, actionConditions);
            }
        }
        this.actionsByEventType = eventTypeIndex;
        this.compiledConditionsByAction = conditionIndex;
        this.conditionEvaluator =
                new ConditionEvaluator(
                        agentPlan
                                .getConfig()
                                .get(AgentConfigOptions.CONDITION_EVALUATION_FAILURE_STRATEGY));
    }

    /**
     * Returns actions whose trigger conditions match the event.
     *
     * <p>Exact event-type matches precede expression matches, and each action appears at most once.
     */
    public List<Action> match(Event event) {
        Set<Action> eventTypeMatches =
                actionsByEventType.getOrDefault(event.getType(), Collections.emptySet());
        List<Action> matchingActions = new ArrayList<>(eventTypeMatches);

        for (Map.Entry<Action, List<ConditionExpressionCompiler.CompiledCondition>> entry :
                compiledConditionsByAction.entrySet()) {
            Action action = entry.getKey();
            if (eventTypeMatches.contains(action)) {
                continue;
            }
            for (ConditionExpressionCompiler.CompiledCondition condition : entry.getValue()) {
                if (conditionEvaluator.evaluate(condition, event)) {
                    matchingActions.add(action);
                    break;
                }
            }
        }
        return matchingActions;
    }
}
