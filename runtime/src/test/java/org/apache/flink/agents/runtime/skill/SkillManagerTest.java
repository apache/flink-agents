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
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void getSkillDirsThrowsForUnknownName() {
        SkillManager manager = new SkillManager(configFromResources());
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.getSkillDirs(List.of("does-not-exist")));
        assertTrue(ex.getMessage().contains("does-not-exist"));
        assertTrue(ex.getMessage().contains("github"));
    }

    @Test
    void resolveResourcePathLocatesBundledFile() {
        SkillManager manager = new SkillManager(configFromResources());
        Path resolved = manager.resolveResourcePath("nano-banana-pro", "scripts/generate_image.py");
        assertNotNull(resolved);
        assertTrue(Files.isRegularFile(resolved));
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

    private static HttpServer startZipServer(byte[] zipBytes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, zipBytes.length);
                    exchange.getResponseBody().write(zipBytes);
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        return server;
    }

    @Test
    void urlOnlyLoadsSkills(@TempDir Path tempDir) throws IOException {
        Path src = Path.of("src/test/resources/skills").toAbsolutePath();
        Path zip = tempDir.resolve("skills.zip");
        zipDir(src, zip);
        HttpServer server = startZipServer(Files.readAllBytes(zip));
        try {
            int port = server.getAddress().getPort();
            Skills config = Skills.fromUrl("http://127.0.0.1:" + port + "/skills.zip");
            SkillManager manager = new SkillManager(config);
            assertEquals(
                    List.of("github", "nano-banana-pro"),
                    manager.getAllSkillNames().stream().sorted().collect(Collectors.toList()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownSchemeFailsLoud() {
        Skills config = new Skills(List.of(new SkillSourceSpec("future-scheme", Map.of("k", "v"))));
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new SkillManager(config));
        assertTrue(ex.getMessage().contains("future-scheme"));
        // The registered-scheme list comes from the wrapped IllegalArgumentException.
        assertTrue(ex.getCause().getMessage().contains("local"));
        assertTrue(ex.getCause().getMessage().contains("classpath"));
    }

    @Test
    void classpathOnlyLoadsSkills() {
        // src/test/resources/skills is on the test classpath.
        Skills config = Skills.fromClasspath("skills");
        SkillManager manager = new SkillManager(config);
        assertEquals(
                List.of("github", "nano-banana-pro"),
                manager.getAllSkillNames().stream().sorted().collect(Collectors.toList()));
    }

    @Test
    void originIsAttachedAfterLoad() {
        SkillManager manager = new SkillManager(configFromResources());
        String localPath = Path.of("src/test/resources/skills").toAbsolutePath().toString();
        SkillOrigin origin = manager.getSkill("github").getOrigin();
        assertNotNull(origin);
        assertEquals("local", origin.getScheme());
        assertEquals(localPath, origin.getLocation());
    }

    @Test
    void duplicateSkillNameLastWriteWinsWithNewOrigin(@TempDir Path tempDir) throws IOException {
        // Two distinct local source dirs each contain a single skill named "dup".
        Path dirA = tempDir.resolve("a");
        Path dirB = tempDir.resolve("b");
        writeSkillUnder(dirA, "dup", "from-a");
        writeSkillUnder(dirB, "dup", "from-b");
        Skills config =
                new Skills(
                        List.of(
                                new SkillSourceSpec("local", Map.of("path", dirA.toString())),
                                new SkillSourceSpec("local", Map.of("path", dirB.toString()))));
        SkillManager manager = new SkillManager(config);
        // Second source wins on collision.
        AgentSkill skill = manager.getSkill("dup");
        assertEquals(dirB.toString(), skill.getOrigin().getLocation());
        // (The duplicate WARN is logged via SLF4J during construction; not asserted here
        //  to avoid a logback test-appender dependency. The origin check above proves
        //  the second registration overwrote the first.)
    }

    private static void writeSkillUnder(Path baseDir, String skillName, String tag)
            throws IOException {
        Path skillDir = baseDir.resolve(skillName);
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: "
                        + skillName
                        + "\ndescription: dummy skill ("
                        + tag
                        + ")\n---\nbody "
                        + tag);
    }

    @Test
    void closeReleasesUrlRepoTempDir(@TempDir Path tempDir) throws Exception {
        Path src = Path.of("src/test/resources/skills").toAbsolutePath();
        Path zip = tempDir.resolve("skills.zip");
        zipDir(src, zip);
        HttpServer server = startZipServer(Files.readAllBytes(zip));
        try {
            int port = server.getAddress().getPort();
            Skills config = Skills.fromUrl("http://127.0.0.1:" + port + "/skills.zip");
            SkillManager manager = new SkillManager(config);
            Path dir = manager.getSkillDir("github");
            assertNotNull(dir);
            // The URL repo materializes into a temp dir whose parent is the per-repo extract root.
            Path extractRoot = dir.getParent();
            assertTrue(Files.exists(dir));
            assertTrue(Files.exists(extractRoot));
            manager.close();
            assertTrue(
                    !Files.exists(extractRoot),
                    "SkillManager.close() must release the URL repo's temp dir");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mixedSourcesAllBranchesExecute(@TempDir Path tempDir) throws IOException {
        Path src = Path.of("src/test/resources/skills").toAbsolutePath();
        Path zip = tempDir.resolve("skills.zip");
        zipDir(src, zip);
        HttpServer server = startZipServer(Files.readAllBytes(zip));
        try {
            int port = server.getAddress().getPort();
            Skills config =
                    new Skills(
                            List.of(
                                    new SkillSourceSpec("local", Map.of("path", src.toString())),
                                    new SkillSourceSpec(
                                            "url",
                                            Map.of(
                                                    "url",
                                                    "http://127.0.0.1:" + port + "/skills.zip")),
                                    new SkillSourceSpec(
                                            "classpath", Map.of("resource", "skills"))));
            SkillManager manager = new SkillManager(config);
            assertEquals(
                    List.of("github", "nano-banana-pro"),
                    manager.getAllSkillNames().stream().sorted().collect(Collectors.toList()));
            // Dispatch order follows the sources list (local -> url -> classpath); last-wins
            // dispatch makes every final repo a ClasspathSkillRepository if all three
            // branches actually ran. In the test classpath, "skills" resolves to
            // target/test-classes/skills (a directory on disk); the local branch would
            // point at src/test/resources/skills instead. Asserting the resolved dir is
            // under "target/test-classes" proves the classpath branch ran last.
            for (String name : manager.getAllSkillNames()) {
                Path dir = manager.getSkillDir(name);
                assertNotNull(dir);
                assertTrue(
                        dir.toString().contains("target/test-classes"),
                        "expected " + dir + " to be under target/test-classes");
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * Minimal {@link SkillRepository} for lifecycle tests: each instance owns one fake skill and
     * records whether {@code close()} was invoked. May be configured to throw a {@link
     * RuntimeException} on close ({@link SkillRepository#close()} declares no checked exceptions;
     * {@link SkillManager#close()} catches {@code Exception} so this still exercises the cascade
     * logic).
     */
    private static final class FakeRepo implements SkillRepository {
        private final AgentSkill skill;
        final AtomicBoolean closed = new AtomicBoolean();
        @javax.annotation.Nullable private final RuntimeException closeException;

        FakeRepo(String skillName) {
            this(skillName, null);
        }

        FakeRepo(String skillName, @javax.annotation.Nullable RuntimeException closeException) {
            this.skill = new AgentSkill(skillName, "fake", "body", null, null, null);
            this.closeException = closeException;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return name.equals(skill.getName()) ? skill : null;
        }

        @Override
        public List<AgentSkill> getSkills() {
            return List.of(skill);
        }

        @Override
        public Map<String, String> getResources(String name) {
            return Map.of();
        }

        @Override
        public void close() {
            closed.set(true);
            if (closeException != null) {
                throw closeException;
            }
        }
    }

    @Test
    void constructorFailureClosesPartiallyLoadedRepos() {
        // Scheme 1 opens one repo successfully; scheme 2 throws on open. The SkillManager
        // constructor must release the first repo before propagating the failure, so the caller
        // (which never receives a SkillManager reference) doesn't leak its temp dir / hook.
        FakeRepo first = new FakeRepo("alpha");
        SkillSourceRegistry.register("test-leak-ok", (params, cl) -> first);
        SkillSourceRegistry.register(
                "test-leak-fail",
                (params, cl) -> {
                    throw new IOException("boom");
                });

        Skills config =
                new Skills(
                        List.of(
                                new SkillSourceSpec("test-leak-ok", Map.of()),
                                new SkillSourceSpec("test-leak-fail", Map.of())));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new SkillManager(config));
        assertTrue(ex.getMessage().contains("test-leak-fail"));
        assertTrue(first.closed.get(), "Repo registered before the failure must be closed");
    }

    @Test
    void constructorFailureSurfacesCleanupErrorAsSuppressed() {
        // Cleanup itself can fail (e.g. disk error during temp-dir delete). The construction
        // failure must remain the primary exception, with the cleanup error attached as
        // suppressed so neither is lost.
        RuntimeException cleanupBoom = new RuntimeException("cleanup-boom");
        FakeRepo bad = new FakeRepo("alpha", cleanupBoom);
        SkillSourceRegistry.register("test-leak-ok-then-cleanup-fail", (params, cl) -> bad);
        SkillSourceRegistry.register(
                "test-leak-fail-after-bad",
                (params, cl) -> {
                    throw new IOException("primary-boom");
                });

        Skills config =
                new Skills(
                        List.of(
                                new SkillSourceSpec("test-leak-ok-then-cleanup-fail", Map.of()),
                                new SkillSourceSpec("test-leak-fail-after-bad", Map.of())));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new SkillManager(config));
        assertEquals("primary-boom", ex.getCause().getMessage());
        assertEquals(1, ex.getSuppressed().length);
        assertSame(cleanupBoom, ex.getSuppressed()[0]);
    }

    @Test
    void closeAttemptsEveryRepoAndRethrowsFirstFailure() throws Exception {
        // Three repos: middle one throws on close. SkillManager.close() must (a) attempt all
        // three (no early-exit), (b) rethrow the first failure, (c) carry any additional
        // failures as suppressed.
        FakeRepo good1 = new FakeRepo("good1");
        RuntimeException badBoom = new RuntimeException("bad-close");
        FakeRepo bad = new FakeRepo("bad", badBoom);
        RuntimeException good2Boom = new RuntimeException("good2-close");
        FakeRepo good2 = new FakeRepo("good2", good2Boom);

        AtomicInteger seq = new AtomicInteger();
        List<FakeRepo> ordered = List.of(good1, bad, good2);
        SkillSourceRegistry.register(
                "test-close-rethrow", (params, cl) -> ordered.get(seq.getAndIncrement()));

        Skills config =
                new Skills(
                        List.of(
                                new SkillSourceSpec("test-close-rethrow", Map.of()),
                                new SkillSourceSpec("test-close-rethrow", Map.of()),
                                new SkillSourceSpec("test-close-rethrow", Map.of())));

        SkillManager manager = new SkillManager(config);
        Exception thrown = assertThrows(Exception.class, manager::close);

        assertTrue(good1.closed.get(), "good1 must be closed");
        assertTrue(bad.closed.get(), "bad must be attempted");
        assertTrue(good2.closed.get(), "good2 must still be closed after bad's failure");
        // The first failure encountered surfaces; the other becomes suppressed. Iteration
        // order over the de-dup set is not specified, so accept either ordering.
        List<Throwable> all = new ArrayList<>();
        all.add(thrown);
        for (Throwable s : thrown.getSuppressed()) {
            all.add(s);
        }
        assertTrue(all.contains(badBoom), "bad-close must surface");
        assertTrue(all.contains(good2Boom), "good2-close must surface");
    }

    @Test
    void closeReleasesRepoDisplacedByDuplicateSkillName() throws Exception {
        // Two sources both contribute a skill named "dup". The skill-name → repo map keeps only
        // the second registration, so iterating it would orphan the first repo. close() must
        // still release it via the openedRepos list.
        FakeRepo first = new FakeRepo("dup");
        FakeRepo second = new FakeRepo("dup");
        AtomicInteger seq = new AtomicInteger();
        List<FakeRepo> ordered = List.of(first, second);
        SkillSourceRegistry.register(
                "test-dup-close", (params, cl) -> ordered.get(seq.getAndIncrement()));

        Skills config =
                new Skills(
                        List.of(
                                new SkillSourceSpec("test-dup-close", Map.of()),
                                new SkillSourceSpec("test-dup-close", Map.of())));

        SkillManager manager = new SkillManager(config);
        manager.close();

        assertTrue(first.closed.get(), "displaced repo must still be closed");
        assertTrue(second.closed.get(), "winning repo must be closed");
    }
}
