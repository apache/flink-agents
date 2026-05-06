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

package org.apache.flink.agents.runtime.skill.repository;

import org.apache.flink.agents.runtime.skill.AgentSkill;
import org.apache.flink.agents.runtime.skill.SkillParser;
import org.apache.flink.agents.runtime.skill.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Filesystem-backed {@link SkillRepository}. Each immediate subdirectory of the configured base
 * directory that contains a {@code SKILL.md} file is treated as a skill.
 *
 * <p>Mirrors the Python {@code
 * flink_agents.runtime.skill.repository.filesystem_repository.FileSystemSkillRepository}.
 */
public class FileSystemSkillRepository implements SkillRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FileSystemSkillRepository.class);

    public static final String SKILL_MD_FILE = "SKILL.md";

    private final Path baseDir;

    public FileSystemSkillRepository(Path baseDir) {
        if (baseDir == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }
        Path resolved = baseDir.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Base directory does not exist: " + resolved);
        }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Base directory is not a directory: " + resolved);
        }
        this.baseDir = resolved;
    }

    public FileSystemSkillRepository(String baseDir) {
        this(Path.of(baseDir));
    }

    public Path getBaseDir() {
        return baseDir;
    }

    @Override
    @Nullable
    public AgentSkill getSkill(String name) {
        Path skillDir = baseDir.resolve(name);
        Path skillMd = skillDir.resolve(SKILL_MD_FILE);
        if (!Files.exists(skillMd)) {
            return null;
        }
        return loadSkill(skillDir);
    }

    @Override
    public List<AgentSkill> getSkills() {
        List<AgentSkill> skills = new ArrayList<>();
        for (String skillName : listSkillNames()) {
            AgentSkill skill = getSkill(skillName);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    @Override
    public Map<String, String> getResources(String name) {
        Path skillDir = baseDir.resolve(name);
        if (!Files.isDirectory(skillDir)) {
            return Collections.emptyMap();
        }
        return loadResources(skillDir);
    }

    private List<String> listSkillNames() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(baseDir)) {
            entries.forEach(
                    entry -> {
                        if (Files.isDirectory(entry)
                                && Files.exists(entry.resolve(SKILL_MD_FILE))) {
                            names.add(entry.getFileName().toString());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list skills under " + baseDir, e);
        }
        names.sort(String::compareTo);
        return names;
    }

    private AgentSkill loadSkill(Path skillDir) {
        Path skillMd = skillDir.resolve(SKILL_MD_FILE);
        if (!Files.exists(skillMd)) {
            return null;
        }
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            AgentSkill skill = SkillParser.parseSkill(content);
            if (!skill.getName().equals(skillDir.getFileName().toString())) {
                LOG.warn(
                        "The skill name {} is different from the base directory {}.",
                        skill.getName(),
                        skillDir.getFileName());
            }
            return skill;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load skill from " + skillDir, e);
        }
    }

    private Map<String, String> loadResources(Path skillDir) {
        Map<String, String> resources = new HashMap<>();
        try (Stream<Path> walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                if (file.getFileName().toString().equals(SKILL_MD_FILE)) {
                                    return;
                                }
                                String rel = skillDir.relativize(file).toString();
                                try {
                                    resources.put(
                                            rel, Files.readString(file, StandardCharsets.UTF_8));
                                } catch (MalformedInputException mie) {
                                    try {
                                        byte[] bytes = Files.readAllBytes(file);
                                        resources.put(
                                                rel,
                                                "base64: "
                                                        + Base64.getEncoder()
                                                                .encodeToString(bytes));
                                    } catch (IOException e) {
                                        LOG.warn(
                                                "Failed to read resource file {} as binary.",
                                                file,
                                                e);
                                    }
                                } catch (IOException e) {
                                    LOG.warn("Failed to read resource file {}.", file, e);
                                }
                            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk skill dir " + skillDir, e);
        }
        return resources;
    }
}
