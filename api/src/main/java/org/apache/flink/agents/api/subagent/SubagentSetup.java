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
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import javax.annotation.Nullable;

/**
 * Caller-facing definition of a sub-agent, registered in the agent plan as an {@code AGENT}
 * resource.
 */
public abstract class SubagentSetup extends SerializableResource {

    /**
     * Prefix of the callable name a sub-agent is exposed to a chat model under. Tools are forbidden
     * to register under this prefix, so a prefixed callable name unambiguously addresses a
     * sub-agent and the executing side routes it to the {@code AGENT} namespace.
     */
    public static final String CALLABLE_NAME_PREFIX = "subagent_";

    /**
     * Tells a caller what this sub-agent is for, so that it can decide whether to delegate to it.
     * This is routing information for the caller, not an instruction for the sub-agent itself.
     */
    @JsonProperty("description")
    private String description;

    /**
     * JSON Schema of the arguments this sub-agent accepts, as declared explicitly. Null when it was
     * not, in which case {@link #getInputSchema()} derives it from {@link #getInputType()}.
     */
    @JsonProperty("input_schema")
    @Nullable
    private String inputSchema;

    protected SubagentSetup() {
        this("");
    }

    protected SubagentSetup(String description) {
        this(description, null);
    }

    protected SubagentSetup(String description, @Nullable String inputSchema) {
        this.description = description == null ? "" : description;
        if (inputSchema != null && inputSchema.isBlank()) {
            throw new IllegalArgumentException("Sub-agent input schema must not be blank.");
        }
        this.inputSchema = inputSchema;
    }

    @Override
    @JsonIgnore
    public ResourceType getResourceType() {
        return ResourceType.AGENT;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Type of the arguments this sub-agent accepts, from which the schema declared to a chat model
     * is derived. Override to type the arguments instead of spelling out their schema; {@link
     * Object}, the default, states no shape.
     *
     * <p>Ignored for JSON, like {@link #getResourceType()}: it is behavior, not state, and writing
     * it would make the plan JSON carry a Java class name the Python side cannot read.
     */
    @JsonIgnore
    public Class<?> getInputType() {
        return Object.class;
    }

    /**
     * Type the result is converted to before it is reported back to the caller. Override to state
     * the shape of a result that is not already JSON-compatible, or to narrow a wider one; {@link
     * Object}, the default, reports the result as it arrived.
     *
     * <p>Ignored for JSON for the same reason as {@link #getInputType()}.
     */
    @JsonIgnore
    public Class<?> getResultType() {
        return Object.class;
    }

    /**
     * JSON Schema of the arguments this sub-agent accepts: the one declared explicitly, else the
     * one derived from {@link #getInputType()}.
     *
     * @return the schema, or {@code null} when neither says anything a model could build a call
     *     from, in which case this sub-agent is not declared to a chat model at all.
     */
    @Nullable
    public String getInputSchema() {
        return inputSchema != null ? inputSchema : InputSchemas.fromType(getInputType());
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
