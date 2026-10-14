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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.subagent.TestSubagentSetup;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.subagent.InternalSubagentPlans;
import org.apache.flink.agents.plan.tools.bash.BashTool;
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
    void externalDescriptorCompilesIntoAgentProvider() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "reviewer", ResourceType.AGENT, new TestSubagentSetup(null).getDescriptor());

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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a ResourceDescriptor");
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
        assertThat(agentProviders.get("child")).isInstanceOf(JavaResourceProvider.class);
        JavaResourceProvider provider = (JavaResourceProvider) agentProviders.get("child");
        assertThat(provider.getDescriptor().getArgument("scope", "")).isEqualTo("child");
        assertThat(InternalSubagentPlans.decode(provider.getDescriptor())).isNotNull();
    }

    /**
     * Serializing the provider must close its nested child plan before writing the provider's own
     * fields, so the type marker stays at the provider level and the plan deserializes back to an
     * equivalent {@link JavaResourceProvider}.
     */
    @Test
    void internalSubagentProviderSurvivesJsonRoundTrip() throws Exception {
        Agent root = new Agent();
        Agent child = new Agent();
        root.addResource("child", ResourceType.AGENT, child);
        AgentPlan plan = new AgentPlan(root);
        JavaResourceProvider original =
                (JavaResourceProvider)
                        plan.getResourceProviders().get(ResourceType.AGENT).get("child");

        ObjectMapper mapper = new ObjectMapper();
        AgentPlan deserialized = mapper.readValue(mapper.writeValueAsString(plan), AgentPlan.class);

        ResourceProvider provider =
                deserialized.getResourceProviders().get(ResourceType.AGENT).get("child");
        assertThat(provider).isInstanceOf(JavaResourceProvider.class);
        JavaResourceProvider roundTripped = (JavaResourceProvider) provider;
        assertThat(roundTripped.getDescriptor().getArgument("scope", "")).isEqualTo("child");
        assertThat(InternalSubagentPlans.decode(roundTripped.getDescriptor())).isNotNull();
        assertThat(InternalSubagentPlans.decode(roundTripped.getDescriptor()).getAgentName())
                .isEqualTo(InternalSubagentPlans.decode(original.getDescriptor()).getAgentName());
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
        JavaResourceProvider first = (JavaResourceProvider) agentProviders.get("first");
        JavaResourceProvider second = (JavaResourceProvider) agentProviders.get("second");
        assertThat(first.getDescriptor().getInitialArguments().get("plan"))
                .isEqualTo(second.getDescriptor().getInitialArguments().get("plan"));
        assertThat(first.getDescriptor().getArgument("scope", "")).isEqualTo("first");
        assertThat(second.getDescriptor().getArgument("scope", "")).isEqualTo("second");
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

    /**
     * Sub-agent callables reach the model under the reserved {@code _subagent_} prefix, so a tool
     * registered under that prefix could never be called and is rejected at plan-construction time.
     */
    @Test
    void toolNameWithTheReservedSubagentPrefixIsRejected() {
        Agent agent = new Agent();
        agent.addResource(
                "_subagent_helper",
                ResourceType.TOOL,
                new BashTool(
                        ResourceDescriptor.Builder.newBuilder(BashTool.class.getName()).build(),
                        null));

        assertThatThrownBy(() -> new AgentPlan(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not start with the reserved prefix '_subagent_'");
    }

    @Test
    void descriptorMetadataSurvivesCompilationAndJsonWithoutMutatingDeclaration() throws Exception {
        Agent child = new Agent();
        ResourceDescriptor declaration =
                new InternalSubagentResourceDescriptorBuilder()
                        .agent(child)
                        .description("Reviews code")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
        Agent root = new Agent();
        root.addResource("reviewer", ResourceType.AGENT, declaration);
        root.addResource("second", ResourceType.AGENT, declaration);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlan restored =
                mapper.readValue(mapper.writeValueAsString(new AgentPlan(root)), AgentPlan.class);
        JavaResourceProvider provider =
                (JavaResourceProvider)
                        restored.getResourceProviders().get(ResourceType.AGENT).get("reviewer");
        assertThat(provider.getDescriptor().getInitialArguments())
                .containsEntry("description", "Reviews code")
                .containsEntry("input_schema", "{\"type\":\"object\"}")
                .containsEntry("scope", "reviewer")
                .doesNotContainKey("agent");
        assertThat(InternalSubagentPlans.plan(provider)).isPresent();
        assertThat(declaration.getInitialArguments())
                .containsEntry("agent", child)
                .doesNotContainKeys("scope", "plan");
    }

    @Test
    void descriptorCycleThroughRootIsRejected() {
        Agent root = new Agent();
        root.addResource(
                "self",
                ResourceType.AGENT,
                new InternalSubagentResourceDescriptorBuilder().agent(root).build());
        assertThatThrownBy(() -> new AgentPlan(root)).hasMessageContaining("Cyclic sub-agent");
    }
}
