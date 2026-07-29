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

package org.apache.flink.agents.integration.test.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.BaseSubagentCallable;
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentSetup;

import javax.annotation.Nullable;

import java.util.List;

/**
 * Mock external sub-agent simulating an HTTP-based external agent system, constructible directly or
 * from a {@link ResourceDescriptor} (the YAML shape). Internal failures are captured into a {@link
 * Result} rather than thrown.
 */
public class MockExternalSubagentSetup extends SubagentSetup {

    private static final long serialVersionUID = 1L;

    private final String endpointUrl;
    private final boolean failOnCall;

    public MockExternalSubagentSetup(String endpointUrl) {
        this(endpointUrl, false);
    }

    @JsonCreator
    public MockExternalSubagentSetup(
            @JsonProperty("endpointUrl") String endpointUrl,
            @JsonProperty("failOnCall") boolean failOnCall) {
        this.endpointUrl = endpointUrl;
        this.failOnCall = failOnCall;
    }

    /** Descriptor-based construction, as used by YAML-declared {@code subagents:} entries. */
    public MockExternalSubagentSetup(
            ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
        this(
                (String) descriptor.getArgument("endpoint"),
                Boolean.TRUE.equals(descriptor.getArgument("fail_on_call")));
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public boolean isFailOnCall() {
        return failOnCall;
    }

    @Override
    protected DurableCallable<Result> asAsyncCallable(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
        return new BaseSubagentCallable(sessionId, callId) {
            @Override
            protected Object callInternal() throws Exception {
                return simulateHttpCall(prompt);
            }
        };
    }

    private List<Object> simulateHttpCall(Object prompt) throws Exception {
        // Token latency standing in for a network round trip.
        Thread.sleep(50);
        if (failOnCall) {
            throw new IllegalStateException("endpoint " + endpointUrl + " is down");
        }
        return List.of("HTTP response for: " + prompt + " from " + endpointUrl);
    }
}
