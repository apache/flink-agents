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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTest {

    private static Skills configFromResources() {
        return Skills.fromLocalDir(
                Path.of("src/test/resources/skills").toAbsolutePath().toString());
    }

    @Test
    void sizeAndAllSkillNames() {
        SkillManager manager = new SkillManager(configFromResources());
        assertEquals(2, manager.size());
        assertEquals(List.of("github", "nano-banana-pro"), manager.getAllSkillNames());
    }

    @Test
    void getSkillThrowsWithAvailableNames() {
        SkillManager manager = new SkillManager(configFromResources());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> manager.getSkill("missing"));
        assertTrue(ex.getMessage().contains("github"));
        assertTrue(ex.getMessage().contains("nano-banana-pro"));
    }

    @Test
    void generateDiscoveryPromptMatchesGoldenFile() throws IOException {
        SkillManager manager = new SkillManager(configFromResources());
        String prompt = manager.generateDiscoveryPrompt(List.of("github", "nano-banana-pro"));
        String expected =
                Files.readString(
                        Path.of("src/test/resources/skill_discovery_prompt.txt"),
                        StandardCharsets.UTF_8);
        assertEquals(expected, prompt);
    }

    @Test
    void getSkillDirsEmptyArgumentReturnsAllFsBacked() {
        SkillManager manager = new SkillManager(configFromResources());
        List<String> dirs = manager.getSkillDirs(List.of());
        assertEquals(2, dirs.size());
        assertTrue(dirs.get(0).endsWith("github") || dirs.get(0).endsWith("nano-banana-pro"));
    }

    @Test
    void getSkillDirsReturnsNamedSkillsInOrder() {
        SkillManager manager = new SkillManager(configFromResources());
        List<String> dirs = manager.getSkillDirs(List.of("github"));
        assertEquals(1, dirs.size());
        assertTrue(dirs.get(0).endsWith("github"));
    }

    @Test
    void resolveResourcePathLocatesBundledFile() {
        SkillManager manager = new SkillManager(configFromResources());
        Path resolved = manager.resolveResourcePath("nano-banana-pro", "scripts/generate_image.py");
        assertNotNull(resolved);
        assertTrue(Files.isRegularFile(resolved));
    }
}
