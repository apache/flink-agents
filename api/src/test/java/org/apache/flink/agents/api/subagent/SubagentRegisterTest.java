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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** Tests registering sub-agents as AGENT resources. */
class SubagentRegisterTest {

    @Test
    void registerSubagentDescriptorAsResource() {
        Agent agent = new Agent();
        ResourceDescriptor setup = new TestSubagentSetup(null).getDescriptor();
        agent.addResource("reviewer", ResourceType.AGENT, setup);

        Map<String, Object> agentResources = agent.getResources().get(ResourceType.AGENT);
        assertEquals(1, agentResources.size());
        assertSame(setup, agentResources.get("reviewer"));
    }

    @Test
    void duplicateNameThrows() {
        Agent agent = new Agent();
        agent.addResource(
                "reviewer", ResourceType.AGENT, new TestSubagentSetup(null).getDescriptor());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agent.addResource(
                                "reviewer",
                                ResourceType.AGENT,
                                new TestSubagentSetup(null).getDescriptor()));
    }

    @Test
    void multipleSubagentsRegistered() {
        Agent agent = new Agent();
        ResourceDescriptor reviewer = new TestSubagentSetup(null).getDescriptor();
        ResourceDescriptor coder = new TestSubagentSetup(null).getDescriptor();
        agent.addResource("reviewer", ResourceType.AGENT, reviewer);
        agent.addResource("coder", ResourceType.AGENT, coder);

        Map<String, Object> agentResources = agent.getResources().get(ResourceType.AGENT);
        assertEquals(2, agentResources.size());
        assertSame(reviewer, agentResources.get("reviewer"));
        assertSame(coder, agentResources.get("coder"));
    }

    @Test
    void rejectsSetupInstances() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Agent()
                                .addResource(
                                        "reviewer",
                                        ResourceType.AGENT,
                                        new TestSubagentSetup(null)));
    }

    @Test
    void sharedRegistrationRejectsSetupInstances() {
        AgentsExecutionEnvironment env =
                mock(
                        AgentsExecutionEnvironment.class,
                        withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
        assertThrows(
                IllegalArgumentException.class,
                () -> env.addResource("reviewer", ResourceType.AGENT, new TestSubagentSetup(null)));
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(TestSubagentSetup.class.getName()).build();
        assertSame(env, env.addResource("reviewer", ResourceType.AGENT, descriptor));
    }
}
