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

import org.apache.flink.agents.runtime.skill.repository.SkillDirectoryReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDirectoryReaderTest {

    private static void writeSkill(Path baseDir, String name, String body) throws IOException {
        Path skillDir = baseDir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: dummy skill\n---\n" + body);
    }

    @Test
    void rejectsNullBaseDir() {
        assertThrows(IllegalArgumentException.class, () -> new SkillDirectoryReader(null));
    }

    @Test
    void rejectsNonexistentPath(@TempDir Path tempDir) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillDirectoryReader(tempDir.resolve("missing")));
    }

    @Test
    void rejectsNonDirectoryPath(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir.txt");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class, () -> new SkillDirectoryReader(file));
    }

    @Test
    void getSkillDirReturnsBaseDirSlashName(@TempDir Path tempDir) {
        SkillDirectoryReader reader = new SkillDirectoryReader(tempDir);
        Path dir = reader.getSkillDir("anything");
        // Returns even for missing names — caller verifies existence.
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("anything"), dir);
    }

    @Test
    void getSkillReturnsNullForMissing(@TempDir Path tempDir) {
        SkillDirectoryReader reader = new SkillDirectoryReader(tempDir);
        assertNull(reader.getSkill("missing"));
    }

    @Test
    void getSkillsListsEverySkillSubdir(@TempDir Path tempDir) throws IOException {
        writeSkill(tempDir, "alpha", "body alpha");
        writeSkill(tempDir, "beta", "body beta");
        // A non-skill subdirectory must be ignored.
        Files.createDirectories(tempDir.resolve("not-a-skill"));

        SkillDirectoryReader reader = new SkillDirectoryReader(tempDir);
        List<AgentSkill> skills = reader.getSkills();
        assertEquals(2, skills.size());
        assertEquals("alpha", skills.get(0).getName());
        assertEquals("beta", skills.get(1).getName());
    }

    @Test
    void getResourcesExposesNonSkillMdFiles(@TempDir Path tempDir) throws IOException {
        writeSkill(tempDir, "gamma", "body gamma");
        Path skillDir = tempDir.resolve("gamma");
        Files.writeString(skillDir.resolve("notes.txt"), "hello");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.sh"), "echo hi");

        SkillDirectoryReader reader = new SkillDirectoryReader(tempDir);
        Map<String, String> resources = reader.getResources("gamma");
        assertEquals(2, resources.size());
        assertEquals("hello", resources.get("notes.txt"));
        assertTrue(
                resources.containsKey("scripts/run.sh")
                        || resources.containsKey("scripts\\run.sh"));
    }

    @Test
    void getResourcesReturnsEmptyForMissingSkill(@TempDir Path tempDir) {
        SkillDirectoryReader reader = new SkillDirectoryReader(tempDir);
        assertNotNull(reader.getResources("missing"));
        assertEquals(0, reader.getResources("missing").size());
    }
}
