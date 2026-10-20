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
import org.apache.flink.agents.runtime.context.RunnerContextImpl;

import javax.annotation.Nullable;

import java.util.List;

/**
 * Routing and result contract of one kind of internal event-driven graph, plus the environment its
 * calls run in: the event the graph consumes, the type its recorded result is read back as, and the
 * resource and memory scope it selects.
 *
 * <p>Definitions contain no invocation state, so one instance serves every call of its kind. What
 * differs between two calls of one kind — the identity and the payload — reaches this interface as
 * arguments, never as fields; the per-call half is the {@link SubgraphInvocation} it builds.
 *
 * <p>Cycles and nested calls use the normal action executor, not a second scheduler.
 */
public interface SubgraphDefinition<R> {
    /**
     * Names this kind of graph. It is also the namespace prefix of the durable slot its calls
     * record into, so it is part of the persisted state: changing it orphans results recorded by
     * earlier runs.
     */
    String kind();

    /** Selects only actions belonging to this graph, never the caller's outer listeners. */
    List<Action> matchActions(Event event);

    /** Whether this event is a graph output rather than another action input. */
    boolean isOutput(Event event);

    /** Validates and decodes the outputs once all graph work has finished. */
    R finish(List<Event> outputs);

    /** The type the result recorded in the caller's durable slot is deserialized as. */
    Class<R> resultClass();

    /** Builds the graph's input event when no terminal result is available in the durable slot. */
    Event inputEvent(Object input);

    /**
     * Builds the call under an identity supplied by the kind's entry point, in the environment this
     * kind selects. The identity must be reproducible after failover.
     */
    SubgraphInvocation<R> newInvocation(RunnerContextImpl context, String id, @Nullable Object key);

    /**
     * Whether an ordinary exception from an action body belongs to the call's error result. A kind
     * that answers yes turns its graph's failure into its own terminal result instead of failing
     * the caller. Failures outside action invocation (such as journal persistence and event
     * dispatch), cancellation, JVM errors and framework faults marked {@code UncapturableException}
     * are never delegated to this policy.
     */
    default boolean capturesActionFailures() {
        return false;
    }

    /**
     * The terminal result this call's graph failure becomes, so a failure survives a failover the
     * way a success does. Consulted only when {@link #capturesActionFailures()} is true. The chain
     * rethrows faults marked {@code UncapturableException} before consulting this.
     */
    default R captureActionFailure(Throwable cause) {
        return null;
    }
}
