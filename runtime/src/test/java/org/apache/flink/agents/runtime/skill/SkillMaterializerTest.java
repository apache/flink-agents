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

import com.sun.net.httpserver.HttpServer;
import org.apache.flink.agents.runtime.skill.repository.SkillMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMaterializerTest {

    private static void writeZip(Path zipPath, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    @Test
    void extractsTopLevelEntries(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("skills.zip");
        writeZip(
                zip,
                Map.of(
                        "skill-a/SKILL.md", "---\nname: skill-a\n---\nbody",
                        "skill-b/SKILL.md", "---\nname: skill-b\n---\nbody"));

        try (SkillMaterializer.Materialized m = SkillMaterializer.extractZipSafely(zip)) {
            Path extracted = m.getDir();
            assertTrue(Files.isDirectory(extracted));
            assertTrue(Files.isRegularFile(extracted.resolve("skill-a/SKILL.md")));
            assertTrue(Files.isRegularFile(extracted.resolve("skill-b/SKILL.md")));
        }
    }

    @Test
    void rejectsZipSlipRelative(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("evil.zip");
        writeZip(zip, Map.of("../evil.txt", "pwn"));

        IOException ex =
                assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));
        assertTrue(ex.getMessage().contains("Unsafe zip entry"));
    }

    @Test
    void rejectsZipSlipAbsolute(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("evil-abs.zip");
        writeZip(zip, Map.of("/etc/evil.txt", "pwn"));

        IOException ex =
                assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));
        assertTrue(ex.getMessage().contains("Unsafe zip entry"));
    }

    private static HttpServer startServer(int status, byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        return server;
    }

    @Test
    void downloadsBytes() throws IOException {
        byte[] body = "hello-zip-bytes".getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer(200, body);
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/anything";

            Path file = SkillMaterializer.downloadToTempFile(url, 5_000);
            try {
                assertTrue(Files.isRegularFile(file));
                byte[] read = Files.readAllBytes(file);
                assertEquals("hello-zip-bytes", new String(read, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(file);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void raisesOnHttpError() throws IOException {
        HttpServer server = startServer(404, new byte[0]);
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/missing";

            assertThrows(IOException.class, () -> SkillMaterializer.downloadToTempFile(url, 5_000));
        } finally {
            server.stop(0);
        }
    }

    private static void writeJar(Path jarPath, Map<String, String> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }

    @Test
    void extractClasspathFromJarCopiesEntriesUnderPrefix(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("skills.jar");
        writeJar(
                jar,
                Map.of(
                        "skills/skill-a/SKILL.md", "---\nname: skill-a\n---\nbody",
                        "skills/skill-b/SKILL.md", "---\nname: skill-b\n---\nbody",
                        "other/unrelated.txt", "ignored"));

        URL jarUrl = new URL("jar:" + jar.toUri() + "!/skills");
        try (SkillMaterializer.Materialized m =
                SkillMaterializer.extractClasspathFromJar(jarUrl, "skills")) {
            Path extracted = m.getDir();
            assertTrue(Files.isDirectory(extracted));
            assertTrue(Files.isRegularFile(extracted.resolve("skill-a/SKILL.md")));
            assertTrue(Files.isRegularFile(extracted.resolve("skill-b/SKILL.md")));
            assertTrue(
                    !Files.exists(extracted.resolve("other/unrelated.txt")),
                    "entries outside the prefix should not be copied");
        }
    }

    @Test
    void extractClasspathFromJarsMergesEntries(@TempDir Path tempDir) throws IOException {
        Path jarA = tempDir.resolve("a.jar");
        Path jarB = tempDir.resolve("b.jar");
        writeJar(jarA, Map.of("skills/skill-a/SKILL.md", "---\nname: skill-a\n---\nA"));
        writeJar(jarB, Map.of("skills/skill-b/SKILL.md", "---\nname: skill-b\n---\nB"));

        URL urlA = new URL("jar:" + jarA.toUri() + "!/skills");
        URL urlB = new URL("jar:" + jarB.toUri() + "!/skills");
        try (SkillMaterializer.Materialized m =
                SkillMaterializer.extractClasspathFromJars(
                        java.util.List.of(urlA, urlB), "skills")) {
            Path extracted = m.getDir();
            assertTrue(Files.isRegularFile(extracted.resolve("skill-a/SKILL.md")));
            assertTrue(Files.isRegularFile(extracted.resolve("skill-b/SKILL.md")));
        }
    }

    @Test
    void extractClasspathFromJarsLastWriteWinsOnCollision(@TempDir Path tempDir)
            throws IOException {
        Path jarA = tempDir.resolve("a.jar");
        Path jarB = tempDir.resolve("b.jar");
        writeJar(jarA, Map.of("skills/dup/SKILL.md", "from-A"));
        writeJar(jarB, Map.of("skills/dup/SKILL.md", "from-B"));

        URL urlA = new URL("jar:" + jarA.toUri() + "!/skills");
        URL urlB = new URL("jar:" + jarB.toUri() + "!/skills");
        try (SkillMaterializer.Materialized m =
                SkillMaterializer.extractClasspathFromJars(
                        java.util.List.of(urlA, urlB), "skills")) {
            String content = Files.readString(m.getDir().resolve("dup/SKILL.md"));
            assertEquals("from-B", content, "later jar in the list must win on collision");
        }
    }

    @Test
    void closeRemovesTempDirAndDeregistersHook(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("skills.zip");
        writeZip(zip, Map.of("skill-a/SKILL.md", "---\nname: skill-a\n---\nbody"));

        SkillMaterializer.Materialized m = SkillMaterializer.extractZipSafely(zip);
        Path extracted = m.getDir();
        assertTrue(Files.exists(extracted));

        m.close();
        assertTrue(!Files.exists(extracted), "close() must remove the temp dir");

        // Second close is idempotent.
        m.close();
    }

    @Test
    void borrowedMaterializedDoesNotRemoveDir(@TempDir Path tempDir) {
        SkillMaterializer.Materialized m = SkillMaterializer.Materialized.borrowed(tempDir);
        assertTrue(Files.exists(tempDir));
        m.close();
        assertTrue(Files.exists(tempDir), "borrowed dirs must not be deleted on close");
    }
}
