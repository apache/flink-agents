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
import org.apache.flink.agents.runtime.skill.repository.URLSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class URLSkillRepositoryTest {

    private static Path resourcesRoot() {
        return Path.of("src/test/resources/skills").toAbsolutePath();
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

    private static HttpServer startZipServer(byte[] zipBytes, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(status, zipBytes.length);
                    exchange.getResponseBody().write(zipBytes);
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        return server;
    }

    @Test
    void loadFromUrl(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("skills.zip");
        zipDir(resourcesRoot(), zip);
        byte[] body = Files.readAllBytes(zip);
        HttpServer server = startZipServer(body, 200);
        try {
            int port = server.getAddress().getPort();
            URLSkillRepository repo =
                    new URLSkillRepository(
                            "http://127.0.0.1:" + port + "/skills.zip",
                            sha256(body).toUpperCase(Locale.ROOT),
                            true);
            assertEquals(
                    List.of("github", "nano-banana-pro"),
                    repo.getSkills().stream()
                            .map(AgentSkill::getName)
                            .sorted()
                            .collect(Collectors.toList()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plainHttpRejectedByDefault() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new URLSkillRepository("http://example.com/skills.zip"));
        assertTrue(ex.getMessage().contains("disabled by default"));
    }

    @Test
    void sha256MismatchRejectedBeforeExtraction(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("skills.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../evil.txt"));
            zos.write("pwn".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        HttpServer server = startZipServer(Files.readAllBytes(zip), 200);
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/skills.zip?token=top-secret#fragment";
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new URLSkillRepository(url, "0".repeat(64), true));
            assertTrue(ex.getMessage().contains("SHA-256 mismatch"));
            assertFalse(ex.getMessage().contains("top-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidHostAndPortAreRejectedBeforeDownload() {
        IllegalArgumentException malformedPort =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new URLSkillRepository(
                                        "https://example.com:bad/skills.zip?token=top-secret"));
        assertNull(malformedPort.getCause());
        assertTrue(malformedPort.getMessage().contains("https://example.com:bad/skills.zip"));
        assertFalse(malformedPort.getMessage().contains("top-secret"));

        for (String url :
                List.of("https://:443/skills.zip", "https://example.com:65536/skills.zip")) {
            assertThrows(IllegalArgumentException.class, () -> new URLSkillRepository(url), url);
        }
    }

    @Test
    void invalidHostnameSyntaxIsRejectedBeforeDownload() {
        for (String url :
                List.of(
                        "https://exa_mple.com/skills.zip",
                        "https://tést.com/skills.zip",
                        "https://%65xample.com/skills.zip",
                        "https://-example.com/skills.zip",
                        "https://example-.com/skills.zip",
                        "https://.example.com/skills.zip",
                        "https://example..com/skills.zip",
                        "https://999.999.999.999/skills.zip",
                        "https://127.1/skills.zip",
                        "https://1.2.3/skills.zip",
                        "https://foo.123/skills.zip",
                        "https://foo.1bar/skills.zip",
                        "https://1.2.3.4.5/skills.zip",
                        "https://1.2.3./skills.zip",
                        "https://[v1.foo]/skills.zip",
                        "https://[fe80::1%eth0]/skills.zip")) {
            assertThrows(IllegalArgumentException.class, () -> new URLSkillRepository(url), url);
        }
    }

    @Test
    void userInfoIsRejectedWithoutLeakingSecrets() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new URLSkillRepository(
                                        "https://user:password@example.com/skills.zip"
                                                + "?token=top-secret"));
        assertTrue(ex.getMessage().contains("must not include user info"));
        assertFalse(ex.getMessage().contains("password"));
        assertFalse(ex.getMessage().contains("top-secret"));
    }

    @Test
    void malformedSha256RejectedBeforeDownload() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new URLSkillRepository(
                                        "http://127.0.0.1:1/skills.zip", "invalid", true));
        assertTrue(ex.getMessage().contains("64 hexadecimal"));
    }

    @Test
    void nonHttpUrlRejected() {
        IllegalArgumentException ex1 =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new URLSkillRepository("file:///tmp/skills.zip"));
        assertTrue(ex1.getMessage().contains("Only HTTP(S)"));

        IllegalArgumentException ex2 =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new URLSkillRepository("ftp://example.com/skills.zip"));
        assertTrue(ex2.getMessage().contains("Only HTTP(S)"));
    }

    @Test
    void error404() throws IOException {
        HttpServer server = startZipServer(new byte[0], 404);
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/missing.zip?token=top-secret#fragment";
            IOException error =
                    assertThrows(IOException.class, () -> new URLSkillRepository(url, null, true));
            assertTrue(error.getMessage().contains("Skill URL returned HTTP 404"));
            assertFalse(error.getMessage().contains("top-secret"));
        } finally {
            server.stop(0);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
