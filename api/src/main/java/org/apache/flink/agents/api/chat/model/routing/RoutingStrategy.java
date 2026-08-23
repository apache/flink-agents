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
 * The single extension point for model routing: given a {@link RoutingContext}, return a {@link
 * RoutingDecision} (a chosen candidate, or {@link RoutingDecision#abstain()} to defer to the
 * router's default model).
 *
 * <p>Strategies are <b>pure selection logic</b>: they must not invoke chat models or other external
 * systems inside {@code route()}. LLM-as-router is available as the framework-managed {@link
 * LlmJudgeRoutingStrategy} (via {@code Strategies.llm(...)}): the engine runs the judge call on the
 * observable, durable chat path — never hidden inside a strategy.
 *
 * <p>The deployable shape of a custom strategy is a named class (or descriptor) that serializes
 * with the agent plan to the TaskManagers; a lambda is a local convenience and must be
 * serializable.
 */
@FunctionalInterface
public interface RoutingStrategy extends Serializable {

    /**
     * Select a model for the given routing context.
     *
     * @param context the request messages, prompt args, and candidates
     * @return the routing decision (selected candidate or abstain)
     * @throws Exception if the strategy fails
     */
    RoutingDecision route(RoutingContext context) throws Exception;
}
