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
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanDeclareSkillsTest {

    public static class SingleSkillsAgent extends Agent {
        @org.apache.flink.agents.api.annotation.Skills
        public static Skills mySkills() {
            return Skills.fromLocalDir("/tmp/skill-a", "/tmp/skill-b");
        }
    }

    public static class MultiSkillsAgent extends Agent {
        @org.apache.flink.agents.api.annotation.Skills
        public static Skills first() {
            return Skills.fromLocalDir("/tmp/skill-a", "/tmp/skill-b");
        }

        @org.apache.flink.agents.api.annotation.Skills
        public static Skills second() {
            return Skills.fromLocalDir("/tmp/skill-b", "/tmp/skill-c");
        }
    }

    public static class NoSkillsAgent extends Agent {}

    @Test
    void singleSkillsRegistersConfigAndBuiltInTools() throws Exception {
        AgentPlan plan = new AgentPlan(new SingleSkillsAgent());
        Map<ResourceType, Map<String, ResourceProvider>> providers = plan.getResourceProviders();

        // Skills config under reserved name
        assertNotNull(providers.get(ResourceType.SKILLS));
        ResourceProvider configProvider =
                providers.get(ResourceType.SKILLS).get(Skills.SKILLS_CONFIG);
        assertNotNull(configProvider);
        assertTrue(configProvider instanceof JavaSerializableResourceProvider);

        // load_skill + bash tools as JavaResourceProviders pointing at runtime / plan classes
        Map<String, ResourceProvider> tools = providers.get(ResourceType.TOOL);
        assertNotNull(tools);
        assertTrue(tools.get(Skills.LOAD_SKILL_TOOL) instanceof JavaResourceProvider);
        assertEquals(
                "org.apache.flink.agents.runtime.skill.LoadSkillTool",
                ((JavaResourceProvider) tools.get(Skills.LOAD_SKILL_TOOL))
                        .getDescriptor()
                        .getClazz());
        assertEquals(
                "org.apache.flink.agents.plan.tools.bash.BashTool",
                ((JavaResourceProvider) tools.get(Skills.BASH_TOOL)).getDescriptor().getClazz());
    }

    @Test
    void multipleSkillsMethodsMergePathsWithDeduplication() throws Exception {
        AgentPlan plan = new AgentPlan(new MultiSkillsAgent());
        ResourceProvider configProvider =
                plan.getResourceProviders().get(ResourceType.SKILLS).get(Skills.SKILLS_CONFIG);
        Skills merged =
                (Skills)
                        ((JavaSerializableResourceProvider) configProvider)
                                .provide(
                                        org.apache.flink.agents.api.resource.ResourceContext
                                                .fromGetResource((n, t) -> null));
        // Order is preserved; "/tmp/skill-b" appears once.
        assertEquals(3, merged.getPaths().size());
        assertTrue(merged.getPaths().contains("/tmp/skill-a"));
        assertTrue(merged.getPaths().contains("/tmp/skill-b"));
        assertTrue(merged.getPaths().contains("/tmp/skill-c"));
    }

    @Test
    void noSkillsLeavesNoConfigProvider() throws Exception {
        AgentPlan plan = new AgentPlan(new NoSkillsAgent());
        Map<String, ResourceProvider> skillsMap =
                plan.getResourceProviders().getOrDefault(ResourceType.SKILLS, Map.of());
        assertNull(skillsMap.get(Skills.SKILLS_CONFIG));
        Map<String, ResourceProvider> tools =
                plan.getResourceProviders().getOrDefault(ResourceType.TOOL, Map.of());
        assertNull(tools.get(Skills.LOAD_SKILL_TOOL));
        assertNull(tools.get(Skills.BASH_TOOL));
    }

    @Test
    void programmaticSkillsAddResourceParticipates() throws Exception {
        Agent agent = new NoSkillsAgent();
        agent.addResource("more", ResourceType.SKILLS, Skills.fromLocalDir("/tmp/skill-d"));
        AgentPlan plan = new AgentPlan(agent);
        ResourceProvider configProvider =
                plan.getResourceProviders().get(ResourceType.SKILLS).get(Skills.SKILLS_CONFIG);
        assertNotNull(configProvider);
        Skills merged =
                (Skills)
                        ((JavaSerializableResourceProvider) configProvider)
                                .provide(
                                        org.apache.flink.agents.api.resource.ResourceContext
                                                .fromGetResource((n, t) -> null));
        assertEquals(java.util.List.of("/tmp/skill-d"), merged.getPaths());
    }
}
