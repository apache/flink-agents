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
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.subagent.TestSubagentSetup;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.subagent.InternalSubagentProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests compiling AGENT resources into the agent plan, for both registration shapes: a {@link
 * SubagentSetup} instance (programmatic) and a {@link ResourceDescriptor} (the YAML shape).
 */
public class AgentPlanSubagentResourceTest {

    @Test
    void subagentSetupInstanceCompilesIntoAgentProvider() throws Exception {
        Agent agent = new Agent();
        agent.addResource("reviewer", ResourceType.AGENT, new TestSubagentSetup());

        AgentPlan plan = new AgentPlan(agent);

        Map<String, ResourceProvider> agentProviders =
                plan.getResourceProviders().get(ResourceType.AGENT);
        assertThat(agentProviders).containsKey("reviewer");
        Resource resolved = agentProviders.get("reviewer").provide(null);
        assertThat(resolved).isInstanceOf(SubagentSetup.class);
    }

    @Test
    void agentDescriptorCompilesAndResolvesToSubagentSetup() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "summarizer",
                ResourceType.AGENT,
                ResourceDescriptor.Builder.newBuilder(TestSubagentSetup.class.getName())
                        .addInitialArgument("endpoint", "http://summarizer:8080")
                        .build());

        AgentPlan plan = new AgentPlan(agent);

        Map<String, ResourceProvider> agentProviders =
                plan.getResourceProviders().get(ResourceType.AGENT);
        assertThat(agentProviders).containsKey("summarizer");

        Resource resolved = agentProviders.get("summarizer").provide(null);
        assertThat(resolved).isInstanceOf(TestSubagentSetup.class);
        assertThat(((TestSubagentSetup) resolved).getEndpoint())
                .isEqualTo("http://summarizer:8080");
        assertThat(resolved.getResourceType()).isEqualTo(ResourceType.AGENT);
    }

    @Test
    void nonSubagentAgentResourceIsRejected() {
        Agent agent = new Agent();
        agent.getResources().get(ResourceType.AGENT).put("bad", "not-a-subagent");

        assertThatThrownBy(() -> new AgentPlan(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a SubagentSetup, a ResourceDescriptor, or an Agent");
    }

    @Test
    void childAgentCompilesIntoInternalProvider() throws Exception {
        Agent root = new Agent();
        Agent child = new Agent();
        root.addResource("child", ResourceType.AGENT, child);

        AgentPlan plan = new AgentPlan(root);

        Map<String, ResourceProvider> agentProviders =
                plan.getResourceProviders().get(ResourceType.AGENT);
        assertThat(agentProviders).containsKey("child");
        assertThat(agentProviders.get("child")).isInstanceOf(InternalSubagentProvider.class);
        InternalSubagentProvider provider = (InternalSubagentProvider) agentProviders.get("child");
        assertThat(provider.getScope()).isEqualTo("child");
        assertThat(provider.getChildPlan()).isNotNull();
    }

    @Test
    void sharedChildAgentCompilesToSinglePlan() throws Exception {
        Agent root = new Agent();
        Agent child = new Agent();
        root.addResource("first", ResourceType.AGENT, child);
        root.addResource("second", ResourceType.AGENT, child);

        AgentPlan plan = new AgentPlan(root);

        Map<String, ResourceProvider> agentProviders =
                plan.getResourceProviders().get(ResourceType.AGENT);
        InternalSubagentProvider first = (InternalSubagentProvider) agentProviders.get("first");
        InternalSubagentProvider second = (InternalSubagentProvider) agentProviders.get("second");
        assertThat(first.getChildPlan()).isSameAs(second.getChildPlan());
    }

    @Test
    void cycleNotThroughRootIsRejectedWithCyclePath() {
        Agent root = new Agent();
        Agent a = new Agent();
        Agent b = new Agent();
        root.addResource("a", ResourceType.AGENT, a);
        a.addResource("b", ResourceType.AGENT, b);
        b.addResource("a", ResourceType.AGENT, a);

        assertThatThrownBy(() -> new AgentPlan(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic sub-agent definition detected: a -> b -> a");
    }

    @Test
    void cycleThroughRootIsRejected() {
        Agent root = new Agent();
        Agent b = new Agent();
        root.addResource("b", ResourceType.AGENT, b);
        b.addResource("root", ResourceType.AGENT, root);

        assertThatThrownBy(() -> new AgentPlan(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic sub-agent definition detected")
                .hasMessageContaining("<root> -> b -> root");
    }

    @Test
    void selfReferenceIsRejected() {
        Agent root = new Agent();
        root.addResource("itself", ResourceType.AGENT, root);

        assertThatThrownBy(() -> new AgentPlan(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic sub-agent definition detected")
                .hasMessageContaining("<root> -> itself");
    }
}
