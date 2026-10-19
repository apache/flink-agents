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
package org.apache.flink.agents.runtime.subgraph;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;

import java.util.List;

/**
 * Routing and result contract of an internal event-driven graph.
 *
 * <p>Definitions contain no invocation state. Resource and memory scopes are supplied separately by
 * the caller. Cycles and nested calls use the normal action executor, not a second scheduler.
 */
public interface SubgraphDefinition<R> {
    /** Selects only actions belonging to this graph, never the caller's outer listeners. */
    List<Action> matchActions(Event event);

    /** Whether this event is a graph output rather than another action input. */
    boolean isOutput(Event event);

    /** Validates and decodes the outputs once all graph work has finished. */
    R finish(List<Event> outputs);

    /**
     * Whether an ordinary exception from an action body belongs to the call's error result.
     * Failures outside action invocation (such as journal persistence and event dispatch),
     * cancellation and fatal errors are never delegated to this policy.
     */
    default boolean capturesActionFailures() {
        return false;
    }
}
