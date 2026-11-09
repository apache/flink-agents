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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Building a plan must not contact a declared Java MCP server: tools and prompts are discovered
 * when the operator starts. The endpoint below refuses connections, so any plan-time connection
 * attempt fails the build.
 */
@DisabledOnJre(JRE.JAVA_11)
class AgentPlanMCPDeferredDiscoveryTest {

    /** Agent whose MCP server cannot be reached. */
    static class UnreachableMCPAgent extends Agent {

        @org.apache.flink.agents.api.annotation.MCPServer
        public static ResourceDescriptor unreachableServer() {
            return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", "http://127.0.0.1:1/mcp")
                    .addInitialArgument("timeout", 1)
                    .addInitialArgument("maxRetries", 0)
                    .build();
        }

        @Action(EventType.InputEvent)
        public void process(Event event, RunnerContext ctx) {
            // no-op
        }
    }

    @Test
    void planBuildDoesNotConnectToTheMCPServer() throws Exception {
        AgentPlan plan = new AgentPlan(new UnreachableMCPAgent());

        assertThat(plan.getResourceProviders().get(ResourceType.MCP_SERVER))
                .containsOnlyKeys("unreachableServer");
        assertThat(plan.getResourceProviders()).doesNotContainKeys(ResourceType.TOOL);
        assertThat(plan.getResourceProviders()).doesNotContainKeys(ResourceType.PROMPT);
    }
}
