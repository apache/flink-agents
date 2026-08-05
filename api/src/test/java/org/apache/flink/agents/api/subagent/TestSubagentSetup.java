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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import javax.annotation.Nullable;

/**
 * Shared {@link SubagentSetup} test double, constructible directly or from a {@link
 * ResourceDescriptor} (the YAML shape). A pure api-layer descriptor: invocation behavior lives in
 * the runtime layer, so the {@code submit} forms throw.
 */
public class TestSubagentSetup extends SubagentSetup {

    private static final long serialVersionUID = 1L;

    @Nullable private final String endpoint;
    private final boolean failOnCall;

    public TestSubagentSetup() {
        this(null, false);
    }

    public TestSubagentSetup(@Nullable String endpoint) {
        this(endpoint, false);
    }

    @JsonCreator
    public TestSubagentSetup(
            @JsonProperty("endpoint") @Nullable String endpoint,
            @JsonProperty("failOnCall") boolean failOnCall) {
        this.endpoint = endpoint;
        this.failOnCall = failOnCall;
    }

    /** Descriptor-based construction, as used by YAML-declared {@code subagents:} entries. */
    public TestSubagentSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        this(
                (String) descriptor.getArgument("endpoint"),
                Boolean.TRUE.equals(descriptor.getArgument("fail_on_call")));
    }

    @Nullable
    public String getEndpoint() {
        return endpoint;
    }

    public boolean isFailOnCall() {
        return failOnCall;
    }

    @Override
    public SubagentFuture submit(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
        throw new UnsupportedOperationException(
                "Descriptor-only sub-agent setup; invocation lives in the runtime layer.");
    }

    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
        throw new UnsupportedOperationException(
                "Descriptor-only sub-agent setup; invocation lives in the runtime layer.");
    }

    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt) {
        throw new UnsupportedOperationException(
                "Descriptor-only sub-agent setup; invocation lives in the runtime layer.");
    }
}
