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

import org.apache.flink.agents.runtime.skill.repository.FileSystemSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemSkillRepositoryTest {

    private static Path resourcesRoot() {
        return Path.of("src/test/resources/skills").toAbsolutePath();
    }

    @Test
    void getSkillsReturnsSortedSkillNames() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(resourcesRoot());
        List<AgentSkill> skills = repo.getSkills();
        assertEquals(2, skills.size());
        assertEquals("github", skills.get(0).getName());
        assertEquals("nano-banana-pro", skills.get(1).getName());
    }

    @Test
    void getSkillReturnsNullForUnknown() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(resourcesRoot());
        assertNull(repo.getSkill("does-not-exist"));
    }

    @Test
    void getResourcesReadsBundledFiles() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(resourcesRoot());
        Map<String, String> resources = repo.getResources("nano-banana-pro");
        assertNotNull(resources);
        assertTrue(
                resources.containsKey("scripts/generate_image.py"),
                "expected scripts/generate_image.py to be loaded as a resource");
        assertTrue(resources.containsKey("_meta.json"));
    }

    @Test
    void resourceLoaderIsLazy() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(resourcesRoot());
        AgentSkill skill = repo.getSkill("nano-banana-pro");
        assertNotNull(skill);
        // resources are not loaded until requested through the loader hook.
        skill.setResourceLoader(() -> repo.getResources("nano-banana-pro"));
        assertEquals(2, skill.getResourcePaths().size());
    }

    @Test
    void missingBaseDirRaises(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing");
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileSystemSkillRepository(missing));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    void binaryResourceFallsBackToBase64(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("binary-skill"));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: binary-skill\ndescription: holds a binary resource\n---\n# Body\n",
                StandardCharsets.UTF_8);
        // Bytes that are not valid UTF-8 (start of a 4-byte sequence with no continuation bytes).
        byte[] bad = new byte[] {(byte) 0xF8, (byte) 0x88, (byte) 0x80, (byte) 0x80};
        Files.write(skillDir.resolve("blob.bin"), bad);

        FileSystemSkillRepository repo = new FileSystemSkillRepository(tempDir);
        Map<String, String> resources = repo.getResources("binary-skill");
        assertTrue(resources.get("blob.bin").startsWith("base64: "));
    }
}
