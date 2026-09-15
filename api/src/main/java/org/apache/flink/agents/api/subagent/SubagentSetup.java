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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.util.Objects;

/**
 * Caller-facing definition of a sub-agent, registered in the agent plan as an {@code AGENT}
 * resource.
 */
public abstract class SubagentSetup extends SerializableResource {

    /**
     * The descriptor capturing this setup's construction configuration. A compiled plan carries
     * this descriptor across the JobManager to TaskManager transfer, and a remote task rebuilds an
     * equivalent setup from it through the {@code (ResourceDescriptor, ResourceContext)}
     * constructor. Every setup carries one, so a registered sub-agent is always rebuildable.
     */
    private final ResourceDescriptor descriptor;

    /**
     * Constructs the setup from the descriptor carrying its configuration. This is the only
     * construction path: concrete subclasses expose a public form of it so the framework can
     * rebuild them from a descriptor on a remote task.
     */
    protected SubagentSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        this.descriptor =
                Objects.requireNonNull(
                        descriptor,
                        "A SubagentSetup must carry a ResourceDescriptor so it can be rebuilt on a"
                                + " remote task.");
    }

    /** The descriptor this setup is rebuilt from on a remote task. */
    @JsonIgnore
    public ResourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    @JsonIgnore
    public ResourceType getResourceType() {
        return ResourceType.AGENT;
    }

    /**
     * Issues a new invocation with an implementation-assigned identity. This is the preferred form.
     */
    public abstract SubagentFuture submit(RunnerContext ctx, Object prompt) throws Exception;

    /**
     * Issues an invocation that continues the conversation of an earlier invocation. Pass the
     * {@code sessionId} of the earlier invocation to continue it. The session id is available on
     * the handle returned by that invocation. Whether a conversation can be continued across
     * actions is up to the concrete implementation.
     */
    public abstract SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId)
            throws Exception;

    /**
     * Issues an invocation under the given {@code (sessionId, callId)} identity. This form is
     * reserved for implementation use.
     */
    public abstract SubagentFuture submit(
            RunnerContext ctx, Object prompt, String sessionId, String callId) throws Exception;
}
