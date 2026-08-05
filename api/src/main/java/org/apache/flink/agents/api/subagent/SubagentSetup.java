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

package org.apache.flink.agents.api.subagent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

/**
 * Base descriptor for a sub-agent resource. Serialized into the agent plan as an {@code AGENT}
 * resource.
 *
 * <p>Declares the full {@code submit} form taking the complete {@code (sessionId, callId)} identity
 * — the implementation-side contract outside the caller-facing short forms of {@link Subagent}. The
 * api layer only declares the resource shape; invocation behavior — the deferred handles and the
 * callable for one invocation — and the id assignment backing the short forms live in the runtime
 * layer's sub-agent setup bases, which extend this class.
 */
public abstract class SubagentSetup extends SerializableResource implements Subagent {

    @Override
    @JsonIgnore
    public ResourceType getResourceType() {
        return ResourceType.AGENT;
    }

    /** Issues an invocation under the given {@code (sessionId, callId)} and returns its handle. */
    public abstract SubagentFuture submit(
            RunnerContext ctx, Object prompt, String sessionId, String callId) throws Exception;
}
