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
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests registering sub-agents as AGENT resources. */
class SubagentRegisterTest {

    @Test
    void registerSubagentSetupAsResource() {
        Agent agent = new Agent();
        TestSubagentSetup setup = new TestSubagentSetup();
        agent.addResource("reviewer", ResourceType.AGENT, setup);

        Map<String, Object> agentResources = agent.getResources().get(ResourceType.AGENT);
        assertEquals(1, agentResources.size());
        assertSame(setup, agentResources.get("reviewer"));
        assertEquals(ResourceType.AGENT, setup.getResourceType());
    }

    @Test
    void duplicateNameThrows() {
        Agent agent = new Agent();
        agent.addResource("reviewer", ResourceType.AGENT, new TestSubagentSetup());
        assertThrows(
                IllegalArgumentException.class,
                () -> agent.addResource("reviewer", ResourceType.AGENT, new TestSubagentSetup()));
    }

    @Test
    void multipleSubagentsRegistered() {
        Agent agent = new Agent();
        TestSubagentSetup reviewer = new TestSubagentSetup();
        TestSubagentSetup coder = new TestSubagentSetup();
        agent.addResource("reviewer", ResourceType.AGENT, reviewer);
        agent.addResource("coder", ResourceType.AGENT, coder);

        Map<String, Object> agentResources = agent.getResources().get(ResourceType.AGENT);
        assertEquals(2, agentResources.size());
        assertSame(reviewer, agentResources.get("reviewer"));
        assertSame(coder, agentResources.get("coder"));
    }
}
