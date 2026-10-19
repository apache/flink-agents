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
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Subagent-specific result adapter. The common invocation owns action accounting and completion;
 * the setup retains this reference only to implement session/call lookup for public handles.
 */
public final class InternalSubagentInvocation extends SubgraphInvocation<List<Object>> {
    public InternalSubagentInvocation(
            String id,
            Object key,
            InternalSubagentSetup setup,
            RunnerContextImpl.ResourceScope environment) {
        super(
                id,
                key,
                new Definition(setup),
                RunnerContextImpl.MemoryContext.createChildContext(),
                environment);
    }

    private static final class Definition implements SubgraphDefinition<List<Object>> {
        private final InternalSubagentSetup setup;

        private Definition(InternalSubagentSetup setup) {
            this.setup = setup;
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
        public List<Object> finish(List<Event> outputs) {
            return outputs.stream()
                    .map(event -> OutputEvent.fromEvent(event).getOutput())
                    .collect(Collectors.toList());
        }

        @Override
        public boolean capturesActionFailures() {
            return true;
        }
    }
}
