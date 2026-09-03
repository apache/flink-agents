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

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bridges the user-facing {@link CustomRoutingExecutor} SPI into the engine's {@link
 * RoutingExecutor} contract. The user executor receives the data-only {@link RoutingContext} —
 * never the {@link RunnerContext} — so a custom strategy can select a model but cannot invoke one
 * (no unmetered chat calls inside the decision step).
 *
 * <p>Instantiation lives here — not in the API-layer {@code ModelRouter} — so the router stays pure
 * declaration data. The user executor is constructed once per declaration and cached (weakly keyed
 * by the {@link RoutingStrategy} instance, whose lifetime matches the per-subtask router cache),
 * preserving executor instance state across the requests of one subtask. Construction contract,
 * checked without instantiation at plan time ({@code AgentPlan#validateCustomExecutor}): a {@code
 * (Map<String,Object>)} constructor fed the declaration's arguments, then a no-arg constructor, via
 * the thread context classloader.
 */
final class CustomRoutingExecutorAdapter implements RoutingExecutor {

    /** User executor instances per declaration. Synchronized: shared across subtask threads. */
    private final Map<RoutingStrategy, CustomRoutingExecutor> instances =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public String decisionSource() {
        return ModelRoutingEvent.SOURCE_STRATEGY;
    }

    @Override
    public RoutingDecision route(
            RoutingStrategy strategy, RoutingContext context, RunnerContext ctx) throws Exception {
        CustomRoutingExecutor executor;
        try {
            executor =
                    instances.computeIfAbsent(strategy, CustomRoutingExecutorAdapter::instantiate);
        } catch (WrappedInstantiationFailure wrapped) {
            throw wrapped.cause;
        }
        return executor.route(strategy, context);
    }

    private static CustomRoutingExecutor instantiate(RoutingStrategy strategy) {
        try {
            Class<?> clazz =
                    Class.forName(
                            strategy.getExecutorClass(),
                            true,
                            Thread.currentThread().getContextClassLoader());
            if (!CustomRoutingExecutor.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Custom routing executor '%s' does not implement %s.",
                                strategy.getExecutorClass(),
                                CustomRoutingExecutor.class.getName()));
            }
            try {
                return (CustomRoutingExecutor)
                        clazz.getConstructor(Map.class).newInstance(strategy.getArguments());
            } catch (NoSuchMethodException noMapCtor) {
                return (CustomRoutingExecutor) clazz.getConstructor().newInstance();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception checked) {
            // computeIfAbsent's mapper cannot throw checked exceptions; unwrap at the call site.
            throw new WrappedInstantiationFailure(checked);
        }
    }

    private static final class WrappedInstantiationFailure extends RuntimeException {
        final Exception cause;

        WrappedInstantiationFailure(Exception cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
