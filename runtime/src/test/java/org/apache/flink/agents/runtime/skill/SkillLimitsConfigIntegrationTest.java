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
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.runtime.ResourceCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the {@code SkillOptions} agent config options: a limit set only in the
 * agent configuration must take effect at the bottom of the real path ({@code ResourceCache} ->
 * {@code ResourceContextImpl} -> {@code SkillManager} -> {@code URLSkillRepository} / {@code
 * FileSystemSkillRepository} -> {@code SkillMaterializer}).
 *
 * <p>Every rejection test has a matching control that uses the same archive with no limit set, so a
 * failure can only come from the configured limit.
 */
class SkillLimitsConfigIntegrationTest {

    private static byte[] skillZip(int skills) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (int i = 0; i < skills; i++) {
                zos.putNextEntry(new ZipEntry("skill-" + i + "/SKILL.md"));
                zos.write(
                        ("---\nname: skill-" + i + "\ndescription: demo " + i + "\n---\nbody\n")
                                .getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static ResourceCache cacheWith(Skills skills, AgentConfiguration config) {
        ResourceCache cache =
                new ResourceCache(
                        new HashMap<>(),
                        Thread.currentThread().getContextClassLoader(),
                        () -> null,
                        config);
        cache.put(Skills.SKILLS_CONFIG, ResourceType.SKILLS, skills);
        return cache;
    }

    private static HttpServer serve(byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    // --- FileSystemSkillRepository (local zip) ---

    @Test
    void localZipLoadsWhenNoLimitsConfigured(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));

        try (ResourceCache cache =
                cacheWith(Skills.fromLocalDir(zip.toString()), new AgentConfiguration())) {
            assertEquals(
                    List.of("skill-0", "skill-1", "skill-2"),
                    cache.getResourceContext().getSkillManager().getAllSkillNames().stream()
                            .sorted()
                            .collect(Collectors.toList()));
        }
    }

    @Test
    void configuredEntryLimitRejectsLocalZip(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));
        AgentConfiguration config = new AgentConfiguration();
        config.setInt("skill.source.max-extract-entries", 2);

        try (ResourceCache cache = cacheWith(Skills.fromLocalDir(zip.toString()), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("3 entries")
                            && rootCauseMessage(ex).contains("limit of 2"),
                    "expected the configured entry limit to fire, got: " + rootCauseMessage(ex));
        }
    }

    @Test
    void configuredEntryLimitExactlyAtArchiveSizeStillLoads(@TempDir Path tempDir)
            throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));
        AgentConfiguration config = new AgentConfiguration();
        config.setInt("skill.source.max-extract-entries", 3);

        try (ResourceCache cache = cacheWith(Skills.fromLocalDir(zip.toString()), config)) {
            assertEquals(3, cache.getResourceContext().getSkillManager().getAllSkillNames().size());
        }
    }

    @Test
    void configuredEntryByteLimitRejectsLocalZip(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(1));
        AgentConfiguration config = new AgentConfiguration();
        config.setLong("skill.source.max-extract-entry-bytes", 5L);

        try (ResourceCache cache = cacheWith(Skills.fromLocalDir(zip.toString()), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("per-entry limit of 5"),
                    "expected the configured per-entry limit to fire, got: "
                            + rootCauseMessage(ex));
        }
    }

    @Test
    void configuredTotalByteLimitRejectsLocalZip(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));
        AgentConfiguration config = new AgentConfiguration();
        config.setLong("skill.source.max-extract-total-bytes", 10L);

        try (ResourceCache cache = cacheWith(Skills.fromLocalDir(zip.toString()), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("exceeds the limit of 10 bytes"),
                    "expected the configured total limit to fire, got: " + rootCauseMessage(ex));
        }
    }

    @Test
    void invalidConfiguredLimitFailsInsteadOfFallingBackToDefault(@TempDir Path tempDir)
            throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(1));
        AgentConfiguration config = new AgentConfiguration();
        config.setInt("skill.source.max-extract-entries", 0);

        try (ResourceCache cache = cacheWith(Skills.fromLocalDir(zip.toString()), config)) {
            Exception ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> cache.getResourceContext().getSkillManager());
            assertTrue(ex.getMessage().contains("strictly positive"), ex.getMessage());
        }
    }

    // --- URLSkillRepository ---

    @Test
    void urlSourceLoadsWhenNoLimitsConfigured() throws Exception {
        HttpServer server = serve(skillZip(2));
        try (ResourceCache cache =
                cacheWith(Skills.fromUrlUnsafe(url(server)), new AgentConfiguration())) {
            assertEquals(2, cache.getResourceContext().getSkillManager().getAllSkillNames().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredDownloadLimitRejectsUrlSource() throws Exception {
        byte[] body = skillZip(2);
        HttpServer server = serve(body);
        AgentConfiguration config = new AgentConfiguration();
        config.setLong("skill.source.url.max-download-bytes", body.length - 1L);

        try (ResourceCache cache = cacheWith(Skills.fromUrlUnsafe(url(server)), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("download")
                            && rootCauseMessage(ex).contains(String.valueOf(body.length - 1L)),
                    "expected the configured download limit to fire, got: " + rootCauseMessage(ex));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredDownloadLimitExactlyAtBodySizeStillLoads() throws Exception {
        byte[] body = skillZip(2);
        HttpServer server = serve(body);
        AgentConfiguration config = new AgentConfiguration();
        config.setLong("skill.source.url.max-download-bytes", (long) body.length);

        try (ResourceCache cache = cacheWith(Skills.fromUrlUnsafe(url(server)), config)) {
            assertEquals(2, cache.getResourceContext().getSkillManager().getAllSkillNames().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredEntryLimitRejectsUrlSource() throws Exception {
        HttpServer server = serve(skillZip(3));
        AgentConfiguration config = new AgentConfiguration();
        config.setInt("skill.source.max-extract-entries", 2);

        try (ResourceCache cache = cacheWith(Skills.fromUrlUnsafe(url(server)), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("limit of 2"),
                    "expected the configured entry limit to fire, got: " + rootCauseMessage(ex));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredEntryByteLimitRejectsUrlSource() throws Exception {
        HttpServer server = serve(skillZip(1));
        AgentConfiguration config = new AgentConfiguration();
        config.setLong("skill.source.max-extract-entry-bytes", 5L);

        try (ResourceCache cache = cacheWith(Skills.fromUrlUnsafe(url(server)), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("per-entry limit of 5"),
                    "expected the configured per-entry limit to fire, got: "
                            + rootCauseMessage(ex));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredTotalByteLimitRejectsUrlSource() throws Exception {
        HttpServer server = serve(skillZip(3));
        AgentConfiguration config = new AgentConfiguration();
        config.setLong("skill.source.max-extract-total-bytes", 10L);

        try (ResourceCache cache = cacheWith(Skills.fromUrlUnsafe(url(server)), config)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> cache.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("exceeds the limit of 10 bytes"),
                    "expected the configured total limit to fire, got: " + rootCauseMessage(ex));
        } finally {
            server.stop(0);
        }
    }

    // --- Sub-agent child cache (D15): inherits the root cache's limits ---

    @Test
    void childCacheInheritsRootLimits(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));
        AgentConfiguration config = new AgentConfiguration();
        config.setInt("skill.source.max-extract-entries", 2);

        try (ResourceCache root = cacheWith(Skills.fromLocalDir(zip.toString()), config);
                ResourceCache child =
                        new ResourceCache(
                                new HashMap<>(),
                                Thread.currentThread().getContextClassLoader(),
                                root,
                                null)) {
            Exception ex =
                    assertThrows(
                            Exception.class, () -> child.getResourceContext().getSkillManager());
            assertTrue(
                    rootCauseMessage(ex).contains("limit of 2"),
                    "child cache must use the root's limits, got: " + rootCauseMessage(ex));
        }
    }

    @Test
    void childCacheOwnConfigOverridesRoot(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));
        AgentConfiguration rootConfig = new AgentConfiguration();
        rootConfig.setInt("skill.source.max-extract-entries", 2);
        AgentConfiguration childConfig = new AgentConfiguration();
        childConfig.setInt("skill.source.max-extract-entries", 3);

        try (ResourceCache root = cacheWith(Skills.fromLocalDir(zip.toString()), rootConfig);
                ResourceCache child =
                        new ResourceCache(
                                new HashMap<>(),
                                Thread.currentThread().getContextClassLoader(),
                                root,
                                null,
                                () -> null,
                                childConfig)) {
            child.put(
                    Skills.SKILLS_CONFIG, ResourceType.SKILLS, Skills.fromLocalDir(zip.toString()));
            assertEquals(3, child.getResourceContext().getSkillManager().getAllSkillNames().size());
        }
    }

    @Test
    void childCacheWithoutRootConfigUsesDefaults(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("skills.zip");
        Files.write(zip, skillZip(3));

        try (ResourceCache root = cacheWith(Skills.fromLocalDir(zip.toString()), null);
                ResourceCache child =
                        new ResourceCache(
                                new HashMap<>(),
                                Thread.currentThread().getContextClassLoader(),
                                root,
                                null)) {
            assertEquals(3, child.getResourceContext().getSkillManager().getAllSkillNames().size());
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/skills.zip";
    }
}
