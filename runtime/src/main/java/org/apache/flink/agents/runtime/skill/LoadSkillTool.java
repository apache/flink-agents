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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.runtime.resource.ResourceContextImpl;

import java.nio.file.Path;
import java.util.List;

/**
 * Built-in tool that returns the body of a SKILL.md (default) or a specific bundled resource.
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.skill.skill_tools.LoadSkillTool}. Auto-loaded
 * by {@code AgentPlan.addSkills} when an agent declares any {@code @Skills} method.
 */
public class LoadSkillTool extends Tool {

    private static final String DESCRIPTION =
            "Load a skill's content or a specific resource. Use this to access skill instructions and resources.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"name\":{\"type\":\"string\","
                    + "\"description\":\"The name of the skill to load (e.g., 'pdf-processing').\"},"
                    + "\"path\":{\"type\":\"string\","
                    + "\"description\":\"Optional path to a specific resource within the skill. If not provided, returns the full SKILL.md content.\","
                    + "\"default\":\"SKILL.md\"}},"
                    + "\"required\":[\"name\"]}";

    public LoadSkillTool(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(new ToolMetadata("load_skill", DESCRIPTION, INPUT_SCHEMA));
        this.resourceContext = resourceContext;
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        String name = parameters.getParameter("name", String.class);
        String path =
                parameters.hasParameter("path")
                        ? parameters.getParameter("path", String.class)
                        : "SKILL.md";

        SkillManager manager;
        try {
            manager = resolveSkillManager();
        } catch (Exception e) {
            return ToolResponse.success(
                    "Skill manager not available. No skills have been registered.");
        }
        if (manager == null) {
            return ToolResponse.success(
                    "Skill manager not available. No skills have been registered.");
        }

        AgentSkill skill;
        try {
            skill = manager.getSkill(name);
        } catch (IllegalArgumentException e) {
            List<String> available = manager.getAllSkillNames();
            String availableStr =
                    available.isEmpty() ? "No skills available." : String.join(", ", available);
            return ToolResponse.success(
                    "Skill '" + name + "' not found. Available skills: " + availableStr);
        }

        if (path == null || "SKILL.md".equals(path)) {
            Path skillDir = manager.getSkillDir(name);
            if (skillDir != null) {
                StringBuilder files = new StringBuilder();
                for (String rel : skill.getResourcePaths()) {
                    files.append("<file>")
                            .append(skillDir.resolve(rel))
                            .append("</file>")
                            .append('\n');
                }
                String filesSection = files.length() == 0 ? "" : files.toString().stripTrailing();
                return ToolResponse.success(
                        "<skill_content name=\""
                                + name
                                + "\">\n"
                                + "# Skill: "
                                + name
                                + "\n\n"
                                + skill.getContent().strip()
                                + "\n\n"
                                + "Base directory for this skill: "
                                + skillDir
                                + "\n"
                                + "Relative paths in this skill are relative to this base directory.\n"
                                + "<skill_files>\n"
                                + filesSection
                                + "\n</skill_files>\n"
                                + "</skill_content>");
            }
            return ToolResponse.success(skill.getContent());
        }

        String content = skill.getResource(path);
        if (content == null) {
            return ToolResponse.success(
                    "Resource '"
                            + path
                            + "' not found in skill '"
                            + name
                            + "', Available resources: "
                            + skill.getResourcePaths());
        }
        return ToolResponse.success(content);
    }

    private SkillManager resolveSkillManager() throws Exception {
        if (resourceContext instanceof ResourceContextImpl) {
            return ((ResourceContextImpl) resourceContext).getSkillManager();
        }
        return null;
    }
}
