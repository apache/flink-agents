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

package org.apache.flink.agents.runtime.skill;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.agents.runtime.resource.ResourceContextImpl;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSkillToolTest {

    private static ResourceContextImpl contextWithSkills() {
        Skills skills =
                Skills.fromLocalDir(
                        Path.of("src/test/resources/skills").toAbsolutePath().toString());
        Map<String, Resource> store = new HashMap<>();
        store.put(Skills.SKILLS_CONFIG, skills);
        return new ResourceContextImpl(
                (name, type) -> {
                    if (type == ResourceType.SKILLS) {
                        return store.get(name);
                    }
                    return null;
                });
    }

    private static LoadSkillTool tool(ResourceContextImpl ctx) {
        return new LoadSkillTool(
                new ResourceDescriptor(LoadSkillTool.class.getName(), Map.of()), ctx);
    }

    private static ToolParameters args(String name, String path) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        if (path != null) {
            m.put("path", path);
        }
        return new ToolParameters(m);
    }

    @Test
    void unknownSkillReturnsAvailableList() {
        LoadSkillTool t = tool(contextWithSkills());
        ToolResponse resp = t.call(args("does-not-exist", null));
        String out = (String) resp.getResult();
        assertTrue(out.contains("not found"));
        assertTrue(out.contains("github"));
        assertTrue(out.contains("nano-banana-pro"));
    }

    @Test
    void defaultPathReturnsSkillContentEnvelope() {
        LoadSkillTool t = tool(contextWithSkills());
        ToolResponse resp = t.call(args("github", null));
        String out = (String) resp.getResult();
        assertTrue(out.startsWith("<skill_content name=\"github\">"));
        assertTrue(out.contains("# Skill: github"));
        assertTrue(out.contains("Base directory for this skill: "));
        assertTrue(out.contains("</skill_content>"));
    }

    @Test
    void resourcePathReturnsRawContent() {
        LoadSkillTool t = tool(contextWithSkills());
        ToolResponse resp = t.call(args("nano-banana-pro", "scripts/generate_image.py"));
        String out = (String) resp.getResult();
        // The script file should be returned verbatim (not wrapped in <skill_content>).
        assertTrue(!out.startsWith("<skill_content"));
        assertTrue(out.length() > 0);
    }

    @Test
    void executionMetadataDescribesRequestedSkillResource() {
        Map<String, Object> metadata =
                tool(contextWithSkills()).getToolExecutionMetadata(args("github", "README.md"));

        assertEquals("github", metadata.get(ToolExecutionMetadataKeys.SKILL_NAME));
        assertEquals("README.md", metadata.get(ToolExecutionMetadataKeys.SKILL_RESOURCE_PATH));
    }

    @Test
    void missingResourceReportsAvailable() {
        LoadSkillTool t = tool(contextWithSkills());
        ToolResponse resp = t.call(args("nano-banana-pro", "no-such.txt"));
        String out = (String) resp.getResult();
        assertTrue(out.contains("Resource 'no-such.txt' not found"));
        assertTrue(out.contains("Available resources"));
    }

    @Test
    void noSkillsRegisteredReturnsFriendlyMessage() {
        // Empty resource context — no _skills_config registered.
        ResourceContextImpl ctx = new ResourceContextImpl((name, type) -> null);
        LoadSkillTool t = tool(ctx);
        ToolResponse resp = t.call(args("anything", null));
        assertEquals(
                "Skill manager not available. No skills have been registered.", resp.getResult());
    }
}
