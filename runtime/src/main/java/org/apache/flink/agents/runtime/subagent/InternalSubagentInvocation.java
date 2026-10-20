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

import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.MemoryScope;
import org.apache.flink.agents.runtime.subgraph.SubgraphExecution;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;

import javax.annotation.Nullable;

/**
 * One internal sub-agent call. The common invocation owns routing, action accounting and
 * completion; this class supplies the sub-agent's environment and the identity a bridge names its
 * call by.
 *
 * <p>The result type is the durable one, {@link SubagentResult}, so a replay installs the recorded
 * result into the invocation instead of translating between two shapes.
 */
public final class InternalSubagentInvocation extends SubgraphInvocation<SubagentResult> {

    public InternalSubagentInvocation(
            String id,
            @Nullable Object key,
            InternalSubagentDefinition definition,
            RunnerContextImpl.ResourceScope environment) {
        super(
                id,
                key,
                definition,
                // A sub-agent does not share memory with its caller, so the call gets stores of
                // its own instead of inheriting the enclosing ones.
                MemoryScope.isolated(),
                environment);
    }

    /**
     * Takes the identified call's result for a bridge that polls it: the child's terminal outcome
     * once the graph has quiesced, or {@code null} while it still runs so the bridge can yield the
     * mailbox and come back.
     *
     * <p>The polling turn is {@link SubgraphExecution#tryFinish}, which owns the registry lookup
     * and the null-while-running contract; what stays here is this kind's own identity. A child
     * failure stays inside the returned result rather than being thrown for the bridge to catch.
     */
    @Nullable
    public static SubagentResult tryComplete(
            RunnerContextImpl context, String sessionId, String callId) throws Exception {
        return SubgraphExecution.tryFinish(
                context, InternalSubagentDefinition.identity(sessionId, callId));
    }
}
