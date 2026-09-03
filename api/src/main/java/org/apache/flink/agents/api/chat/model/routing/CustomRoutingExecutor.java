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

import java.io.Serializable;

/**
 * The extension point for user-defined routing: given the strategy declaration and a {@link
 * RoutingContext}, return a {@link RoutingDecision} (a chosen candidate, or {@link
 * RoutingDecision#abstain()} to defer to the router's default model).
 *
 * <p>Custom executors are <b>pure selection logic</b> over request data: the context is data-only
 * by design, so a custom executor cannot invoke chat models or other engine services inside {@code
 * route()} — every model call stays on the engine's durable, metered, observable path.
 * LLM-as-router is available as the framework-managed {@code Strategies.llm(...)} strategy.
 *
 * <p>The deployable shape is a named class carried by the strategy declaration ({@code
 * Strategies.custom(...)}): the class must expose a {@code (Map<String,Object>)} constructor (fed
 * the declaration's arguments) or a no-arg constructor, and is reconstructed by name on the
 * TaskManagers — not shipped as a live closure.
 */
public interface CustomRoutingExecutor extends Serializable {

    /**
     * Select a model for the given routing context.
     *
     * @param strategy the declaration this executor was configured with (type + arguments)
     * @param context the request messages, prompt args, and candidates
     * @return the routing decision (selected candidate or abstain)
     * @throws Exception if the executor fails
     */
    RoutingDecision route(RoutingStrategy strategy, RoutingContext context) throws Exception;
}
