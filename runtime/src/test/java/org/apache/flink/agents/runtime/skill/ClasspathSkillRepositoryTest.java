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

import org.apache.flink.agents.runtime.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathSkillRepositoryTest {

    private static Path resourcesRoot() {
        return Path.of("src/test/resources/skills").toAbsolutePath();
    }

    private static void zipDirIntoJarUnderPrefix(Path src, Path jar, String prefix)
            throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try {
                                    String name = prefix + "/" + src.relativize(file).toString();
                                    jos.putNextEntry(new JarEntry(name));
                                    Files.copy(file, jos);
                                    jos.closeEntry();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
    }

    @Test
    void loadFromDirectoryResource() throws IOException {
        // src/test/resources/skills is on the test classpath as a directory.
        ClasspathSkillRepository repo = new ClasspathSkillRepository("skills");
        assertEquals(
                List.of("github", "nano-banana-pro"),
                repo.getSkills().stream()
                        .map(AgentSkill::getName)
                        .sorted()
                        .collect(Collectors.toList()));
    }

    @Test
    void loadFromJarResource(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("skills-as-jar.jar");
        zipDirIntoJarUnderPrefix(resourcesRoot(), jar, "embedded-skills");
        URLClassLoader loader =
                new URLClassLoader(new URL[] {jar.toUri().toURL()}, /* parent */ null);
        ClasspathSkillRepository repo = new ClasspathSkillRepository("embedded-skills", loader);
        assertEquals(
                List.of("github", "nano-banana-pro"),
                repo.getSkills().stream()
                        .map(AgentSkill::getName)
                        .sorted()
                        .collect(Collectors.toList()));
    }

    @Test
    void loadFromMultipleJarsMergesSkills(@TempDir Path tempDir) throws IOException {
        // Two jars on the classpath, each carrying a different skill under the same prefix.
        // Before review #12 only one of them would be loaded (first match wins, the other
        // silently dropped). After: both must be visible.
        Path skillsRoot = resourcesRoot();
        Path jarA = tempDir.resolve("plugin-a.jar");
        Path jarB = tempDir.resolve("plugin-b.jar");
        zipSingleSkillIntoJarUnderPrefix(skillsRoot.resolve("github"), jarA, "merged-skills");
        zipSingleSkillIntoJarUnderPrefix(
                skillsRoot.resolve("nano-banana-pro"), jarB, "merged-skills");

        URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jarA.toUri().toURL(), jarB.toUri().toURL()}, /* parent */ null);
        ClasspathSkillRepository repo = new ClasspathSkillRepository("merged-skills", loader);
        assertEquals(
                List.of("github", "nano-banana-pro"),
                repo.getSkills().stream()
                        .map(AgentSkill::getName)
                        .sorted()
                        .collect(Collectors.toList()));
    }

    @Test
    void loadFromMultipleJarsWithCollisionLastWins(@TempDir Path tempDir) throws IOException {
        // Two jars both carry the same skill name under the same prefix; the merge must
        // succeed (no exception), and the resulting baseDir contains the content from
        // whichever jar was processed second.
        Path skillsRoot = resourcesRoot();
        Path jarA = tempDir.resolve("plugin-a.jar");
        Path jarB = tempDir.resolve("plugin-b.jar");
        // Both jars contribute under prefix "merged-skills" and both contain "github".
        zipSingleSkillIntoJarUnderPrefix(skillsRoot.resolve("github"), jarA, "merged-skills");
        zipSingleSkillIntoJarUnderPrefix(skillsRoot.resolve("github"), jarB, "merged-skills");

        URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jarA.toUri().toURL(), jarB.toUri().toURL()}, /* parent */ null);
        ClasspathSkillRepository repo = new ClasspathSkillRepository("merged-skills", loader);
        assertEquals(
                List.of("github"),
                List.copyOf(
                        repo.getSkills().stream()
                                .map(AgentSkill::getName)
                                .collect(Collectors.toList())));
    }

    /** Zip a single skill directory into a jar under {@code <prefix>/<skill-name>/...}. */
    private static void zipSingleSkillIntoJarUnderPrefix(Path skillDir, Path jar, String prefix)
            throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try {
                                    String name =
                                            prefix
                                                    + "/"
                                                    + skillDir.getFileName()
                                                    + "/"
                                                    + skillDir.relativize(file).toString();
                                    jos.putNextEntry(new JarEntry(name));
                                    Files.copy(file, jos);
                                    jos.closeEntry();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
    }

    @Test
    void missingResource() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ClasspathSkillRepository("no-such-thing"));
        assertTrue(
                ex.getMessage().contains("Classpath resource not found"),
                "expected 'Classpath resource not found' in message, got: " + ex.getMessage());
    }
}
