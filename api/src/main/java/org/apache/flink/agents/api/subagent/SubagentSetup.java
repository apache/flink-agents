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

import javax.annotation.Nullable;

import java.util.Objects;

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
    public static final String CALLABLE_NAME_PREFIX = "_subagent_";

    /**
     * Descriptor argument key carrying the caller-facing {@link #getDescription()}. It is the
     * single source of truth shared by the constructor that reads it back and the subclasses whose
     * descriptors write it, and the cross-language wire key the Python side reads.
     */
    public static final String FIELD_DESCRIPTION = "description";

    /**
     * Descriptor argument key carrying the explicitly declared {@link #getInputSchema()}, absent
     * when it was not declared. Shared like {@link #FIELD_DESCRIPTION} across the write and read
     * ends so they cannot drift apart.
     */
    public static final String FIELD_INPUT_SCHEMA = "input_schema";

    /**
     * The descriptor capturing this setup's construction configuration. A compiled plan carries
     * this descriptor across the JobManager to TaskManager transfer, and a remote task rebuilds an
     * equivalent setup from it through the {@code (ResourceDescriptor, ResourceContext)}
     * constructor. Every setup carries one, so a registered sub-agent is always rebuildable.
     */
    private final ResourceDescriptor descriptor;

    /**
     * Tells a caller what this sub-agent is for, so that it can decide whether to delegate to it.
     * This is routing information for the caller, not an instruction for the sub-agent itself. It
     * travels as the descriptor's {@value #FIELD_DESCRIPTION} argument, so a remote task reads it
     * back instead of losing it with the live object.
     */
    private final String description;

    /**
     * JSON Schema of the arguments this sub-agent accepts, as declared explicitly. Null when it was
     * not, in which case {@link #getInputSchema()} derives it from {@link #getInputType()}. It
     * travels as the descriptor's {@value #FIELD_INPUT_SCHEMA} argument.
     */
    @Nullable private final String inputSchema;

    /**
     * Constructs the setup from the descriptor carrying its configuration. This is the only
     * construction path: concrete subclasses expose a public form of it so the framework can
     * rebuild them from a descriptor on a remote task. The descriptor must name this setup's own
     * concrete type as its clazz, because that name is what the remote rebuild reflects over; a
     * mismatch is rejected here rather than surfacing as a wrong-class rebuild on a far task. The
     * caller-facing metadata travels as descriptor arguments, so it is read back here rather than
     * passed alongside the descriptor.
     */
    protected SubagentSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        this.descriptor =
                Objects.requireNonNull(
                        descriptor,
                        "A SubagentSetup must carry a ResourceDescriptor so it can be rebuilt on a"
                                + " remote task.");
        if (!getClass().getName().equals(this.descriptor.getClazz())) {
            throw new IllegalArgumentException(
                    String.format(
                            "A %s must carry a descriptor naming its own type, but the descriptor"
                                    + " names %s; a remote task would rebuild the wrong class.",
                            getClass().getName(), this.descriptor.getClazz()));
        }
        String declaredDescription = this.descriptor.getArgument(FIELD_DESCRIPTION);
        this.description = declaredDescription == null ? "" : declaredDescription;
        String declaredInputSchema = this.descriptor.getArgument(FIELD_INPUT_SCHEMA);
        if (declaredInputSchema != null && declaredInputSchema.isBlank()) {
            throw new IllegalArgumentException("Sub-agent input schema must not be blank.");
        }
        this.inputSchema = declaredInputSchema;
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
     *     from; a chat model rejects such a sub-agent at setup time rather than calling it with no
     *     arguments.
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
