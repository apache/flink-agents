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
import org.apache.flink.agents.runtime.python.utils.JavaResourceAdapter;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrossLanguageSkillSourceTest {
    @TempDir Path temp;

    @Test
    void classpathBridgeUsesUserLoaderAndCopiesAllResources() throws Exception {
        Path first = jar("blob_a", "job-a");
        Path second = jar("b.jar", "job-b");
        try (URLClassLoader a = new URLClassLoader(new URL[] {first.toUri().toURL()}, null);
                URLClassLoader b = new URLClassLoader(new URL[] {second.toUri().toURL()}, null);
                URLClassLoader child = new URLClassLoader(new URL[0], a)) {
            Path outA = Files.createDirectory(temp.resolve("out-a"));
            Path outB = Files.createDirectory(temp.resolve("out-b"));
            new JavaResourceAdapter(null, child).extractClasspathSkills("skills", outA.toString());
            new JavaResourceAdapter(null, b).extractClasspathSkills("skills", outB.toString());
            assertThat(Files.readString(outA.resolve("demo/example.txt"))).isEqualTo("job-a");
            assertThat(Files.readString(outB.resolve("demo/example.txt"))).isEqualTo("job-b");
            assertThat(outA.resolve("demo/SKILL.md")).exists();
            assertThatThrownBy(
                            () ->
                                    new JavaResourceAdapter(null, a)
                                            .extractClasspathSkills("missing", outA.toString()))
                    .hasMessageContaining("Classpath resource not found: missing");
        }
    }

    @Test
    void packageRepositoriesUseTheirOwnInterpreterAndOwnTheirDirectories() throws Exception {
        PythonInterpreterManager first = mock(PythonInterpreterManager.class);
        PythonInterpreterManager second = mock(PythonInterpreterManager.class);
        AtomicReference<Path> directoryA = new AtomicReference<>();
        AtomicReference<Path> directoryB = new AtomicReference<>();
        stubPackage(first, directoryA, "attachment-a");
        stubPackage(second, directoryB, "attachment-b");
        try (SkillManager a =
                        new SkillManager(
                                Skills.fromPackage("pkg", "skills"),
                                getClass().getClassLoader(),
                                first);
                SkillManager b =
                        new SkillManager(
                                Skills.fromPackage("pkg", "skills"),
                                getClass().getClassLoader(),
                                second)) {
            assertThat(a.loadSkillResource("demo", "example.txt")).isEqualTo("attachment-a");
            assertThat(b.loadSkillResource("demo", "example.txt")).isEqualTo("attachment-b");
            assertThat(a.getSkill("demo").getOrigin().getScheme()).isEqualTo("package");
            Path originalDirectory = directoryA.get();
            // Re-open through A's factory after B exists; a shared handler would now use B.
            try (SkillRepository reopened =
                    a.getHandler("package")
                            .open(
                                    java.util.Map.of("package", "pkg", "resource", "skills"),
                                    getClass().getClassLoader())) {
                assertThat(reopened.getResources("demo").get("example.txt"))
                        .isEqualTo("attachment-a");
            }
            assertThat(directoryA.get()).doesNotExist();
            directoryA.set(originalDirectory);
        }
        assertThat(directoryA.get()).doesNotExist();
        assertThat(directoryB.get()).doesNotExist();
        verify(first, never()).close();
        verify(second, never()).close();
    }

    private void stubPackage(
            PythonInterpreterManager interpreter,
            AtomicReference<Path> directory,
            String attachment) {
        doAnswer(
                        invocation -> {
                            Path target = Path.of(invocation.getArgument(3, String.class));
                            directory.set(target);
                            Path skill = Files.createDirectory(target.resolve("demo"));
                            Files.writeString(
                                    skill.resolve("SKILL.md"),
                                    "---\nname: demo\ndescription: Test\n---\nBody");
                            Files.writeString(skill.resolve("example.txt"), attachment);
                            return null;
                        })
                .when(interpreter)
                .invoke(
                        eq("python_java_utils.materialize_package_skills"),
                        eq("pkg"),
                        eq("skills"),
                        anyString());
    }

    @Test
    void packageInitializationFailureReleasesPartialDirectory() {
        AtomicReference<Path> directory = new AtomicReference<>();
        PythonInterpreterManager interpreter = mock(PythonInterpreterManager.class);
        doAnswer(
                        invocation -> {
                            Path target = Path.of(invocation.getArgument(3, String.class));
                            directory.set(target);
                            Files.writeString(target.resolve("partial"), "partial");
                            throw new IOException("missing package");
                        })
                .when(interpreter)
                .invoke(
                        eq("python_java_utils.materialize_package_skills"),
                        eq("missing"),
                        eq("skills"),
                        anyString());
        assertThatThrownBy(
                        () ->
                                new SkillManager(
                                        Skills.fromPackage("missing", "skills"),
                                        getClass().getClassLoader(),
                                        interpreter))
                .hasMessageContaining("missing/skills")
                .hasRootCauseMessage("missing package");
        assertThat(directory.get()).doesNotExist();
        assertThatThrownBy(() -> new SkillManager(Skills.fromPackage("missing", "skills")))
                .hasRootCauseMessage(
                        "Python package skill source 'missing/skills' requires an initialized Python runtime bridge.");
    }

    private Path jar(String name, String content) throws IOException {
        Path jar = temp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            // Deliberately omit directory entries, as real shaded JARs can do.
            out.putNextEntry(new JarEntry("skills/demo/SKILL.md"));
            out.write(
                    "---\nname: demo\ndescription: Test\n---\nBody"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("skills/demo/example.txt"));
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
