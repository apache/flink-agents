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

import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.runtime.skill.repository.FileSystemSkillRepository;

import javax.annotation.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and indexes all skills referenced by a {@link Skills} configuration.
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.skill.skill_manager.SkillManager}.
 */
public class SkillManager {

    private final Skills config;
    private final Map<String, AgentSkill> skills = new LinkedHashMap<>();
    private final Map<String, SkillRepository> repos = new HashMap<>();

    public SkillManager(Skills config) {
        this.config = config;
        loadFromPaths();
    }

    public int size() {
        return skills.size();
    }

    public AgentSkill getSkill(String name) {
        AgentSkill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException(
                    "Skill "
                            + name
                            + " not found, available skill names are: "
                            + getAllSkillNames());
        }
        return skill;
    }

    public List<String> getAllSkillNames() {
        return new ArrayList<>(skills.keySet());
    }

    @Nullable
    public String loadSkillResource(String skillName, String resourcePath) {
        return getSkill(skillName).getResource(resourcePath);
    }

    /** Build the {@code <available_skills>} system prompt for the given skill names. */
    public String generateDiscoveryPrompt(List<String> names) {
        if (size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(SkillPromptProvider.SKILL_DISCOVERY_PROMPT);
        for (String name : names) {
            AgentSkill skill = getSkill(name);
            sb.append(
                    String.format(
                            SkillPromptProvider.AVAILABLE_SKILL_TEMPLATE,
                            skill.getName(),
                            skill.getDescription()));
        }
        sb.append(SkillPromptProvider.AVAILABLE_SKILLS_TAG_END);
        return sb.toString();
    }

    /**
     * Absolute directory paths for the listed skill names (filesystem-backed only). When called
     * with an empty or {@code null} list, returns directories for all filesystem-backed skills.
     */
    public List<String> getSkillDirs(List<String> names) {
        Iterable<String> selected = (names == null || names.isEmpty()) ? repos.keySet() : names;
        List<String> dirs = new ArrayList<>();
        for (String skillName : selected) {
            SkillRepository repo = repos.get(skillName);
            if (repo instanceof FileSystemSkillRepository) {
                Path dir = ((FileSystemSkillRepository) repo).getBaseDir().resolve(skillName);
                dirs.add(dir.toString());
            }
        }
        return dirs;
    }

    /** Return absolute directory path for a single skill, if filesystem-backed. */
    @Nullable
    public Path getSkillDir(String skillName) {
        SkillRepository repo = repos.get(skillName);
        if (repo instanceof FileSystemSkillRepository) {
            return ((FileSystemSkillRepository) repo).getBaseDir().resolve(skillName);
        }
        return null;
    }

    /** Resolve a skill resource's relative path to an absolute path, or {@code null} if missing. */
    @Nullable
    public Path resolveResourcePath(String skillName, String resourcePath) {
        SkillRepository repo = repos.get(skillName);
        if (repo instanceof FileSystemSkillRepository) {
            Path resolved =
                    ((FileSystemSkillRepository) repo)
                            .getBaseDir()
                            .resolve(skillName)
                            .resolve(resourcePath);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private void loadFromPaths() {
        for (String path : config.getPaths()) {
            FileSystemSkillRepository repo = new FileSystemSkillRepository(path);
            for (AgentSkill skill : repo.getSkills()) {
                final String skillName = skill.getName();
                skill.setResourceLoader(() -> repo.getResources(skillName));
                skills.put(skillName, skill);
                repos.put(skillName, repo);
            }
        }
    }
}
