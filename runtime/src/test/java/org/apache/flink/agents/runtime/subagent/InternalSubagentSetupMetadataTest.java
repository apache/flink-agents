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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentMetadataProvider;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.subagent.InternalSubagentProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caller-facing metadata an agent declares through {@link SubagentMetadataProvider} reaches the
 * materialized {@link InternalSubagentSetup}, which is what a chat model needs to call the
 * sub-agent as a tool. Covers the full reflective construction path used at runtime, plus the
 * metadata-less shape an agent without the capability compiles to.
 */
class InternalSubagentSetupMetadataTest {

    private static final String DESCRIPTION = "Reviews pull requests and reports findings";
    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}}}";

    private static class MetadataAgent extends Agent implements SubagentMetadataProvider {

        @Override
        public String getSubagentDescription() {
            return DESCRIPTION;
        }

        @Override
        public String getSubagentInputSchema() {
            return INPUT_SCHEMA;
        }
    }

    @Test
    void declaredMetadataReachesMaterializedSetup() throws Exception {
        InternalSubagentProvider provider = compileRootWith(new MetadataAgent());

        InternalSubagentSetup setup = (InternalSubagentSetup) provider.provide(null);

        assertThat(setup.getScope()).isEqualTo("reviewer");
        assertThat(setup.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(setup.getInputSchema()).isEqualTo(INPUT_SCHEMA);
    }

    @Test
    void agentWithoutMetadataMaterializesToNormalizedDefaults() throws Exception {
        InternalSubagentProvider provider = compileRootWith(new Agent());

        InternalSubagentSetup setup = (InternalSubagentSetup) provider.provide(null);

        assertThat(setup.getScope()).isEqualTo("reviewer");
        assertThat(setup.getDescription()).isEmpty();
        assertThat(setup.getInputSchema()).isNull();
    }

    private static InternalSubagentProvider compileRootWith(Agent child) throws Exception {
        Agent root = new Agent();
        root.addResource("reviewer", ResourceType.AGENT, child);
        return (InternalSubagentProvider)
                new AgentPlan(root).getResourceProviders().get(ResourceType.AGENT).get("reviewer");
    }
}
