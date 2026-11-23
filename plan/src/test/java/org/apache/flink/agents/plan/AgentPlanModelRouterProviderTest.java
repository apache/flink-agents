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
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Java model router declaration carries a placeholder for its class. The plan names the router
 * resource before it creates the provider, when it compiles an agent and when it restores a plan,
 * so the generic Java provider instantiates it.
 */
class AgentPlanModelRouterProviderTest {

    private static final String ROUTER_RESOURCE =
            org.apache.flink.agents.plan.routing.ModelRouter.class.getName();

    private static ResourceDescriptor declaration() {
        return ModelRouter.of("small", "big")
                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                .defaultModel("small")
                .build();
    }

    private static AgentPlan compiledWith(ResourceDescriptor router) throws Exception {
        Agent agent = new Agent();
        agent.addResource("router", ResourceType.MODEL_ROUTER, router);
        return new AgentPlan(agent);
    }

    private static void assertProvidesTheRouterResource(
            AgentPlan plan, ResourceDescriptor declaration) throws Exception {
        JavaResourceProvider provider =
                (JavaResourceProvider)
                        plan.getResourceProviders().get(ResourceType.MODEL_ROUTER).get("router");
        assertThat(provider.getDescriptor().getClazz()).isEqualTo(ROUTER_RESOURCE);
        assertThat(provider.getDescriptor().getInitialArguments())
                .isEqualTo(declaration.getInitialArguments());
        Resource resource = provider.provide(null);
        assertThat(resource).isInstanceOf(org.apache.flink.agents.plan.routing.ModelRouter.class);
        assertThat(
                        ((org.apache.flink.agents.plan.routing.ModelRouter) resource)
                                .getCandidateNames())
                .containsExactly("small", "big");
    }

    @Test
    void aBuilderDeclarationIsProvidedAsTheRouterResource() throws Exception {
        ResourceDescriptor declaration = declaration();
        assertThat(declaration.getClazz()).isEqualTo(ResourceName.ModelRouter.MODEL_ROUTER);
        assertProvidesTheRouterResource(compiledWith(declaration), declaration);
    }

    @Test
    void aDeclarationThatNamesAnotherClassIsProvidedAsTheRouterResource() throws Exception {
        // Descriptors named the api class before the declaration and the resource were two
        // classes, and a YAML entry may still spell it out. The declaration does not choose the
        // class, so the plan names the router resource whatever the descriptor says.
        for (String named : List.of(ModelRouter.class.getName(), "example.OtherRouter")) {
            ResourceDescriptor declaration =
                    new ResourceDescriptor(named, declaration().getInitialArguments());
            assertProvidesTheRouterResource(compiledWith(declaration), declaration);
        }
    }

    @Test
    void aCompiledPlanRestoresWithTheRouterResource() throws Exception {
        ResourceDescriptor declaration = declaration();
        ObjectMapper mapper = new ObjectMapper();
        String compiled = mapper.writeValueAsString(compiledWith(declaration));
        assertThat(compiled).contains(ROUTER_RESOURCE);
        assertProvidesTheRouterResource(mapper.readValue(compiled, AgentPlan.class), declaration);
    }

    @Test
    void aPlanCompiledBeforeTheSplitRestoresWithTheRouterResource() throws Exception {
        ResourceDescriptor declaration = declaration();
        ObjectMapper mapper = new ObjectMapper();
        String compiled = mapper.writeValueAsString(compiledWith(declaration));
        // Before the split the compiled plan named the api class, which was the resource then.
        String compiledBefore = compiled.replace(ROUTER_RESOURCE, ModelRouter.class.getName());
        assertThat(compiledBefore).isNotEqualTo(compiled).doesNotContain(ROUTER_RESOURCE);
        assertProvidesTheRouterResource(
                mapper.readValue(compiledBefore, AgentPlan.class), declaration);
    }

    @Test
    void aRestoredJavaProviderWithAPythonDescriptorIsStillRejected() {
        // Only a Java descriptor is the router resource's to name. A Java provider whose descriptor
        // says Python is a malformed plan, and keeps failing the provider's own check.
        String provider =
                "{\"name\":\"router\",\"type\":\"model_router\",\"descriptor\":{"
                        + "\"target_module\":\"flink_agents.api.chat_models.routing\","
                        + "\"target_clazz\":\"ModelRouter\","
                        + "\"arguments\":{\"candidates\":[\"small\"]},"
                        + "\"language\":\"python\"},"
                        + "\"__resource_provider_type__\":\"JavaResourceProvider\"}";
        assertThatThrownBy(() -> new ObjectMapper().readValue(provider, ResourceProvider.class))
                .hasStackTraceContaining("JavaResourceProvider requires a Java descriptor");
    }
}
