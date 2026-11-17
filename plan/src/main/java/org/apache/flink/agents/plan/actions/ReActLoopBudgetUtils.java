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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ReActLoopBudget;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Request-scoped loop progress stored in sensory memory as checkpoint-safe values. */
final class ReActLoopBudgetUtils {
    private ReActLoopBudgetUtils() {}

    private static final Logger LOG = LoggerFactory.getLogger(ReActLoopBudgetUtils.class);
    static final String LOOP_BUDGET_CONTEXT = "_LOOP_BUDGET_CONTEXT";

    static void initialize(RunnerContext ctx, ChatRequestEvent event) throws Exception {
        if (getBudgetState(ctx.getSensoryMemory(), event.getId()) != null) return;
        ReActLoopBudget limits = event.getBudget();
        if (limits == null && ctx.getConfig() != null) {
            var conf = ctx.getConfig();
            limits =
                    ReActLoopBudget.builder()
                            .maxTurns(conf.get(AgentExecutionOptions.LOOP_MAX_TURNS))
                            .maxTime(conf.get(AgentExecutionOptions.LOOP_MAX_TIME))
                            .maxTokens(conf.get(AgentExecutionOptions.LOOP_MAX_TOKENS))
                            .mode(conf.get(AgentExecutionOptions.LOOP_BUDGET_MODE))
                            .build();
        }
        if (limits != null) {
            Map<String, Object> budgetState = new HashMap<>();
            if (limits.getMaxTurns() != null) budgetState.put("turns_left", limits.getMaxTurns());
            if (limits.getMaxTokens() != null)
                budgetState.put("tokens_left", limits.getMaxTokens());
            if (limits.getMaxTime() != null) {
                budgetState.put("max_time_ms", limits.getMaxTime().toMillis());
                // Persist before any routing or model call, independently of action completion.
                long startedAt =
                        ctx.durableExecute(
                                new DurableCallable<Long>() {
                                    @Override
                                    public String getId() {
                                        return "react-loop:budget-start";
                                    }

                                    @Override
                                    public Class<Long> getResultClass() {
                                        return Long.class;
                                    }

                                    @Override
                                    public Long call() {
                                        return System.currentTimeMillis();
                                    }
                                });
                budgetState.put("started_at_ms", startedAt);
            }
            if (!budgetState.isEmpty()) {
                budgetState.put("mode", limits.getMode().name());
                ReActLoopBudgetUtils.saveBudgetState(
                        ctx.getSensoryMemory(), event.getId(), budgetState);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Map<String, Object>> getBudgetContexts(MemoryObject memory)
            throws Exception {
        return memory.isExist(LOOP_BUDGET_CONTEXT)
                ? (Map<UUID, Map<String, Object>>) memory.get(LOOP_BUDGET_CONTEXT).getValue()
                : Map.of();
    }

    static Map<String, Object> getBudgetState(MemoryObject memory, UUID requestId)
            throws Exception {
        return getBudgetContexts(memory).get(requestId);
    }

    static void saveBudgetState(
            MemoryObject memory, UUID requestId, Map<String, Object> budgetState) throws Exception {
        Map<UUID, Map<String, Object>> all = new HashMap<>(getBudgetContexts(memory));
        all.put(requestId, new HashMap<>(budgetState));
        memory.set(LOOP_BUDGET_CONTEXT, all);
    }

    static boolean isSoftLimit(MemoryObject memory, UUID requestId) throws Exception {
        Map<String, Object> budgetState = getBudgetState(memory, requestId);
        return budgetState != null && "SOFT".equals(budgetState.get("mode"));
    }

    /** Checks all limits and consumes one turn only when ordinary inference may start. */
    static String tryStartTurn(MemoryObject memory, UUID requestId) throws Exception {
        return tryStartTurn(memory, requestId, System.currentTimeMillis());
    }

    static String tryStartTurn(MemoryObject memory, UUID requestId, long nowMillis)
            throws Exception {
        Map<String, Object> budgetState = getBudgetState(memory, requestId);
        if (budgetState == null) return null;
        if (budgetState.containsKey("max_time_ms")) {
            long elapsed =
                    Math.max(
                            0L,
                            nowMillis - ((Number) budgetState.get("started_at_ms")).longValue());
            if (elapsed >= ((Number) budgetState.get("max_time_ms")).longValue())
                return "TIME_BUDGET_EXCEEDED";
        }
        if (budgetState.containsKey("tokens_left")
                && ((Number) budgetState.get("tokens_left")).longValue() == 0)
            return "TOKEN_BUDGET_EXCEEDED";
        if (budgetState.containsKey("turns_left")) {
            long remaining = ((Number) budgetState.get("turns_left")).longValue();
            if (remaining == 0) return "TURN_BUDGET_EXCEEDED";
            budgetState = new HashMap<>(budgetState);
            budgetState.put("turns_left", remaining - 1);
            saveBudgetState(memory, requestId, budgetState);
        }
        return null;
    }

    static void updateTokenBudgetState(MemoryObject memory, UUID id, ChatResult response)
            throws Exception {
        if (response != null) updateTokenUsage(memory, id, response.getUsage());
    }

    static void updateTokenUsage(MemoryObject memory, UUID id, TokenUsage usage) throws Exception {
        Map<String, Object> budgetState = getBudgetState(memory, id);
        if (budgetState == null || !budgetState.containsKey("tokens_left")) return;
        Long input = usage == null ? null : usage.getPromptTokens();
        Long output = usage == null ? null : usage.getCompletionTokens();
        if (input == null && output == null) {
            LOG.warn("Missing token usage for chat request {}", id);
            return;
        }
        long left = ((Number) budgetState.get("tokens_left")).longValue();
        left = Math.max(0L, left - (input == null ? 0 : input));
        budgetState = new HashMap<>(budgetState);
        budgetState.put("tokens_left", Math.max(0L, left - (output == null ? 0 : output)));
        saveBudgetState(memory, id, budgetState);
    }
}
