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
package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;

import javax.annotation.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An internal sub-agent's graph: the child plan's routing table and output contract, the child
 * scope it runs in, and the identity scheme naming one of its calls. Every output the child emits
 * accumulates into one result.
 *
 * <p>A call is named by a session id and a call id, both assigned by the caller's deterministic
 * allocator, so one session can hold several calls and a replay issues the same call under the same
 * id and finds the recorded result.
 *
 * <p>A child action's failure is the call's error result rather than its caller's failure, so it
 * survives a failover the way a success does.
 */
final class InternalSubagentDefinition implements SubgraphDefinition<SubagentResult> {

    private final InternalSubagentSetup setup;

    InternalSubagentDefinition(InternalSubagentSetup setup) {
        this.setup = setup;
    }

    @Override
    public String kind() {
        return "subagent";
    }

    /** The sub-agent-scope name of one call of a session. */
    static String identity(String sessionId, String callId) {
        return sessionId + "#" + callId;
    }

    @Override
    public Class<SubagentResult> resultClass() {
        return SubagentResult.class;
    }

    @Override
    public Event inputEvent(Object input) {
        return new InputEvent(input);
    }

    /** A sub-agent graph runs in the child scope, so the call gets the child's environment. */
    @Override
    public SubgraphInvocation<SubagentResult> newInvocation(
            RunnerContextImpl context, String id, @Nullable Object key) {
        return new InternalSubagentInvocation(id, key, this, setup.childScope(context));
    }

    @Override
    public List<Action> matchActions(Event event) {
        return setup.matchActions(event);
    }

    @Override
    public boolean isOutput(Event event) {
        return OutputEvent.EVENT_TYPE.equals(event.getType());
    }

    @Override
    public SubagentResult finish(List<Event> outputs) {
        return SubagentResult.ok(
                outputs.stream()
                        .map(event -> OutputEvent.fromEvent(event).getOutput())
                        .collect(Collectors.toList()));
    }

    @Override
    public boolean capturesActionFailures() {
        return true;
    }

    @Override
    public SubagentResult captureActionFailure(Throwable cause) {
        return SubagentResult.error(
                cause instanceof Exception ? (Exception) cause : new IllegalStateException(cause));
    }
}
