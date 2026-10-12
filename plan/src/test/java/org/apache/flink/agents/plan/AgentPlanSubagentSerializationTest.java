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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An external sub-agent registered programmatically as a live {@link SubagentSetup} instance must
 * survive the plan's serialization round-trip — the transfer that carries a compiled plan from the
 * JobManager to a remote TaskManager — and resolve there to an equivalent setup carrying the same
 * configuration. In-process the plan resolves to the live object, so only the round-trip exercises
 * the descriptor-based rebuild on the far side.
 */
public class AgentPlanSubagentSerializationTest {

    @Test
    void liveExternalSubagentIsRebuiltAfterPlanSerializationRoundTrip() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "reviewer",
                ResourceType.AGENT,
                new ExternalReviewSubagent("http://review.internal:8080", 3));

        AgentPlan plan = new AgentPlan(agent);

        // The JobManager -> TaskManager transfer: AgentPlan crosses the process boundary through
        // its Serializable hooks, so the live setup object does not travel with it and must be
        // rebuilt from what does.
        AgentPlan restored = serializeRoundTrip(plan);

        Map<String, ResourceProvider> agentProviders =
                restored.getResourceProviders().get(ResourceType.AGENT);
        assertThat(agentProviders).containsKey("reviewer");

        Resource resolved = agentProviders.get("reviewer").provide(null);

        assertThat(resolved).isInstanceOf(ExternalReviewSubagent.class);
        ExternalReviewSubagent reviewer = (ExternalReviewSubagent) resolved;
        assertThat(reviewer.getEndpoint()).isEqualTo("http://review.internal:8080");
        assertThat(reviewer.getMaxRetries()).isEqualTo(3);
        assertThat(resolved.getResourceType()).isEqualTo(ResourceType.AGENT);
    }

    /**
     * Round-trips the plan through Java serialization, the transfer that carries it to a remote
     * TaskManager. {@link AgentPlan} exposes no {@code serialize()} method: its public contract is
     * {@code Serializable}, whose private {@code writeObject}/{@code readObject} hooks Java invokes
     * automatically, so writing the plan to an {@link ObjectOutputStream} is that entry point.
     */
    private static AgentPlan serializeRoundTrip(AgentPlan plan)
            throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(plan);
        }
        try (ObjectInputStream in =
                new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (AgentPlan) in.readObject();
        }
    }

    /**
     * A user-defined external sub-agent in the documented shape: construction configuration held in
     * final fields and set through a parameterized constructor, with no Jackson creator.
     */
    public static class ExternalReviewSubagent extends SubagentSetup {

        private static final String FIELD_ENDPOINT = "endpoint";
        private static final String FIELD_MAX_RETRIES = "max_retries";

        private final String endpoint;
        private final int maxRetries;

        public ExternalReviewSubagent(String endpoint, int maxRetries) {
            this(
                    ResourceDescriptor.Builder.newBuilder(ExternalReviewSubagent.class.getName())
                            .addInitialArgument(FIELD_ENDPOINT, endpoint)
                            .addInitialArgument(FIELD_MAX_RETRIES, maxRetries)
                            .build(),
                    null);
        }

        public ExternalReviewSubagent(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
            this.endpoint = descriptor.getArgument(FIELD_ENDPOINT);
            this.maxRetries = descriptor.getArgument(FIELD_MAX_RETRIES, 0);
        }

        public String getEndpoint() {
            return endpoint;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) {
            throw new UnsupportedOperationException("serialization is under test, not invocation");
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
            throw new UnsupportedOperationException("serialization is under test, not invocation");
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            throw new UnsupportedOperationException("serialization is under test, not invocation");
        }
    }
}
