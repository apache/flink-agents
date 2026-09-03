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

import org.apache.flink.agents.api.chat.model.routing.CustomRoutingExecutor;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ModelRoutingEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bridges the user-facing {@link CustomRoutingExecutor} SPI into the engine's {@link
 * RoutingExecutor} contract. The user executor receives the data-only {@link RoutingContext} —
 * never the {@link RunnerContext} — so a custom strategy can select a model but cannot invoke one
 * (no unmetered chat calls inside the decision step).
 *
 * <p>Instantiation lives here — not in the API-layer {@code ModelRouter} — so the router stays pure
 * declaration data. It happens in {@link #prepare}, <i>outside</i> the persistence boundary: a
 * transiently failing user constructor throws fresh on every request instead of being persisted as
 * the decision's durable record and replayed forever. The instance is cached per declaration in
 * thread-confined state (routing always runs on the task's mailbox thread), so executor instance
 * state spans the requests of one subtask and is released with the task's thread — no locks, and no
 * pinning of a cancelled job's user classloader by JVM-global state. Construction contract, checked
 * without instantiation at plan time ({@code AgentPlan#validateCustomExecutor}): a {@code
 * (Map<String,Object>)} constructor fed the declaration's arguments, then a no-arg constructor, via
 * the thread context classloader.
 */
final class CustomRoutingExecutorAdapter implements RoutingExecutor {

    /**
     * User executor instances per declaration, confined to the task mailbox thread. Weak keys keep
     * a long-lived thread from accumulating entries for re-registered routers within one task.
     */
    private final ThreadLocal<Map<RoutingStrategy, CustomRoutingExecutor>> instances =
            ThreadLocal.withInitial(WeakHashMap::new);

    @Override
    public String decisionSource() {
        return ModelRoutingEvent.SOURCE_STRATEGY;
    }

    @Override
    public void prepare(RoutingStrategy strategy, RunnerContext ctx) throws Exception {
        Map<RoutingStrategy, CustomRoutingExecutor> cache = instances.get();
        if (!cache.containsKey(strategy)) {
            cache.put(strategy, instantiate(strategy));
        }
    }

    @Override
    public RoutingDecision route(
            RoutingStrategy strategy, RoutingContext context, RunnerContext ctx) throws Exception {
        CustomRoutingExecutor executor = instances.get().get(strategy);
        if (executor == null) {
            // Unreachable through the resolver (prepare() runs first); kept as a loud guard.
            throw new IllegalStateException("Custom routing executor was not prepared.");
        }
        return executor.route(strategy, context);
    }

    private static CustomRoutingExecutor instantiate(RoutingStrategy strategy) throws Exception {
        Class<?> clazz =
                Class.forName(
                        strategy.getExecutorClass(),
                        true,
                        Thread.currentThread().getContextClassLoader());
        if (!CustomRoutingExecutor.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Custom routing executor '%s' does not implement %s.",
                            strategy.getExecutorClass(), CustomRoutingExecutor.class.getName()));
        }
        try {
            return (CustomRoutingExecutor)
                    clazz.getConstructor(Map.class).newInstance(strategy.getArguments());
        } catch (NoSuchMethodException noMapCtor) {
            return (CustomRoutingExecutor) clazz.getConstructor().newInstance();
        }
    }
}
