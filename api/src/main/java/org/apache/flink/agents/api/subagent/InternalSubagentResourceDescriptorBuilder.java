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

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.Objects;

/** Builds an ordinary resource descriptor declaring an internal sub-agent. */
public final class InternalSubagentResourceDescriptorBuilder {
    public static final String SETUP_CLASS =
            "org.apache.flink.agents.runtime.subagent.InternalSubagentSetup";
    public static final String AGENT = "agent";
    public static final String PLAN = "plan";
    public static final String SCOPE = "scope";

    private Agent agent;
    private String description = "";
    private String inputSchema;
    private Class<?> inputType;

    public InternalSubagentResourceDescriptorBuilder() {}

    public InternalSubagentResourceDescriptorBuilder agent(Agent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
        return this;
    }

    public InternalSubagentResourceDescriptorBuilder description(String description) {
        this.description = Objects.requireNonNull(description, "description");
        return this;
    }

    public InternalSubagentResourceDescriptorBuilder inputSchema(String inputSchema) {
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        return this;
    }

    public InternalSubagentResourceDescriptorBuilder inputType(Class<?> inputType) {
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        return this;
    }

    public ResourceDescriptor build() {
        Objects.requireNonNull(agent, "An internal sub-agent requires an agent");
        if (inputType != null && inputSchema != null) {
            throw new IllegalArgumentException("Declare either inputType or inputSchema, not both");
        }
        String schema = inputType == null ? inputSchema : InputSchemas.fromType(inputType);
        if (inputType != null && schema == null) {
            throw new IllegalArgumentException("Sub-agent inputType must describe an object");
        }
        if (schema != null && schema.isBlank()) {
            throw new IllegalArgumentException("Sub-agent input schema must not be blank");
        }
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(SETUP_CLASS)
                        .addInitialArgument(AGENT, agent)
                        .addInitialArgument(SubagentSetup.FIELD_DESCRIPTION, description);
        if (schema != null) {
            builder.addInitialArgument(SubagentSetup.FIELD_INPUT_SCHEMA, schema);
        }
        return builder.build();
    }
}
