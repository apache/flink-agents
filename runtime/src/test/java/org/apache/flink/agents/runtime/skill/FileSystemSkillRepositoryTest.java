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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    private static void zipDir(Path src, Path dstZip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dstZip));
                Stream<Path> walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try {
                                    String name = src.relativize(file).toString();
                                    zos.putNextEntry(new ZipEntry(name));
                                    Files.copy(file, zos);
                                    zos.closeEntry();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
    }

    @Test
    void loadFromZip(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("skills.zip");
        zipDir(resourcesRoot(), zip);

        FileSystemSkillRepository repo = new FileSystemSkillRepository(zip);

        List<AgentSkill> skills = repo.getSkills();
        assertEquals(2, skills.size());
        assertEquals("github", skills.get(0).getName());
        assertEquals("nano-banana-pro", skills.get(1).getName());
        // Resources resolve through the materialized base directory.
        Map<String, String> resources = repo.getResources("nano-banana-pro");
        assertTrue(resources.containsKey("_meta.json"));
    }

    @Test
    void invalidPathKindRaises(@TempDir Path tempDir) throws IOException {
        Path nonZipFile = tempDir.resolve("data.txt");
        Files.writeString(nonZipFile, "not a zip");
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileSystemSkillRepository(nonZipFile));
        assertTrue(ex.getMessage().contains("must be a directory or a .zip"));
    }

    @Test
    void binaryResourceRoundTripsThroughBase64(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("binary-skill"));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: binary-skill\ndescription: holds a binary resource\n---\n# Body\n",
                StandardCharsets.UTF_8);
        // PNG signature + a sprinkling of non-UTF-8 bytes — verifies actual base64 encoding,
        // not just the "base64: " marker. Asserting only the prefix once let a Python-side
        // f-string bug (raw bytes-repr instead of base64) hide for an entire review cycle.
        byte[] original =
                new byte[] {
                    (byte) 0x89,
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                    (byte) 0xF8,
                    (byte) 0x88,
                    (byte) 0x80,
                    (byte) 0x80
                };
        Files.write(skillDir.resolve("blob.bin"), original);

        FileSystemSkillRepository repo = new FileSystemSkillRepository(tempDir);
        String encoded = repo.getResources("binary-skill").get("blob.bin");

        assertTrue(encoded.startsWith("base64: "));
        byte[] decoded = Base64.getDecoder().decode(encoded.substring("base64: ".length()));
        assertArrayEquals(original, decoded);
    }
}
