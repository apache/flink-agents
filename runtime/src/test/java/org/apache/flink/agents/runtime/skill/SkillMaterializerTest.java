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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

            Path file = SkillMaterializer.downloadToTempFile(url, 5_000, true);
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

            assertThrows(
                    IOException.class,
                    () -> SkillMaterializer.downloadToTempFile(url, 5_000, true));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPlainHttpByDefault() {
        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                SkillMaterializer.downloadToTempFile(
                                        "http://127.0.0.1:1/anything", 5_000));
        assertTrue(ex.getMessage().contains("disabled by default"));
    }

    @Test
    void malformedUrlDoesNotLeakRawInput() {
        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                SkillMaterializer.downloadToTempFile(
                                        "not-a-url?token=top-secret", 5_000, true));
        assertTrue(!ex.getMessage().contains("top-secret"));
        assertTrue(ex.getCause() == null);
    }

    @Test
    void unfollowedCrossProtocolRedirectFailsClearly() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "https://example.com/skills.zip");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            "http://127.0.0.1:" + port + "/redirect", 5_000, true));
            assertTrue(ex.getMessage().contains("unsupported redirect"));
            assertTrue(ex.getMessage().contains("https://example.com/skills.zip"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectWithoutLocationFailsClearly() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            "http://127.0.0.1:" + port + "/redirect", 5_000, true));
            assertTrue(ex.getMessage().contains("invalid redirect"));
            assertTrue(ex.getMessage().contains("<redacted>"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void follows308Redirect() throws IOException {
        byte[] body = "redirected-zip-bytes".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", baseUrl + "/skills.zip");
                    exchange.sendResponseHeaders(308, -1);
                    exchange.close();
                });
        server.createContext(
                "/skills.zip",
                exchange -> {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            Path file = SkillMaterializer.downloadToTempFile(baseUrl + "/redirect", 5_000, true);
            try {
                assertEquals("redirected-zip-bytes", Files.readString(file));
            } finally {
                Files.deleteIfExists(file);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectUserInfoBeforeRequestWithoutLeakingSecrets() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        AtomicInteger targetRequests = new AtomicInteger();
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://user:password@127.0.0.1:"
                                            + port
                                            + "/skills.zip?token=top-secret");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/skills.zip",
                exchange -> {
                    targetRequests.incrementAndGet();
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                });
        server.start();
        try {
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            baseUrl + "/redirect", 5_000, true));
            assertTrue(ex.getMessage().contains("must not include user info"));
            assertTrue(!ex.getMessage().contains("password"));
            assertTrue(!ex.getMessage().contains("top-secret"));
            assertEquals(0, targetRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEleventhDistinctRedirect() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        AtomicInteger requests = new AtomicInteger();
        server.createContext(
                "/chain",
                exchange -> {
                    requests.incrementAndGet();
                    String path = exchange.getRequestURI().getPath();
                    int step = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                    exchange.getResponseHeaders().add("Location", "/chain/" + (step + 1));
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.start();
        try {
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            baseUrl + "/chain/0", 5_000, true));
            assertTrue(ex.getMessage().contains("too many redirects"));
            assertEquals(11, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectLocationWithRawSpace() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        AtomicInteger targetRequests = new AtomicInteger();
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", baseUrl + "/skills archive.zip");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/",
                exchange -> {
                    targetRequests.incrementAndGet();
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                });
        server.start();
        try {
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            baseUrl + "/redirect", 5_000, true));
            assertTrue(ex.getMessage().contains("Invalid skill URL"));
            assertEquals(0, targetRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void respectsJvmWideDisabledRedirects() throws IOException {
        byte[] body = "redirected-zip-bytes".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/skills.zip");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/skills.zip",
                exchange -> {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        boolean redirectsOriginallyEnabled = HttpURLConnection.getFollowRedirects();
        HttpURLConnection.setFollowRedirects(false);
        try {
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            baseUrl + "/redirect", 5_000, true));
            assertTrue(ex.getMessage().contains("unsupported redirect"));
            assertTrue(ex.getMessage().contains(baseUrl + "/skills.zip"));
        } finally {
            HttpURLConnection.setFollowRedirects(redirectsOriginallyEnabled);
            server.stop(0);
        }
    }

    @Test
    void logsSanitizedEffectiveUrlForSameProtocolRedirect() throws IOException {
        byte[] body = "redirected-zip-bytes".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    baseUrl
                                            + "/skills.zip?redirect_token=secret"
                                            + "#redirect-fragment");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/skills.zip",
                exchange -> {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        TestAppender appender = new TestAppender("SkillMaterializerRedirectAppender");
        appender.start();
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        AbstractConfiguration configuration =
                (AbstractConfiguration) loggerContext.getConfiguration();
        configuration.addAppender(appender);
        String loggerName = SkillMaterializer.class.getName();
        LoggerConfig previousLoggerConfig = configuration.getLoggers().get(loggerName);
        LoggerConfig loggerConfig =
                new LoggerConfig(loggerName, org.apache.logging.log4j.Level.WARN, false);
        loggerConfig.addAppender(appender, org.apache.logging.log4j.Level.WARN, null);
        configuration.addLogger(loggerName, loggerConfig);
        loggerContext.updateLoggers();
        try {
            String configuredUrl = baseUrl + "/redirect?configured_token=secret";
            Path file = SkillMaterializer.downloadToTempFile(configuredUrl, 5_000, true);
            try {
                assertEquals("redirected-zip-bytes", Files.readString(file));
                String warning = String.join("\n", appender.getMessages());
                assertTrue(warning.contains(baseUrl + "/redirect"));
                assertTrue(warning.contains(baseUrl + "/skills.zip"));
                assertTrue(!warning.contains("configured_token"));
                assertTrue(!warning.contains("redirect_token"));
                assertTrue(!warning.contains("redirect-fragment"));
            } finally {
                Files.deleteIfExists(file);
            }
        } finally {
            configuration.removeLogger(loggerName);
            if (previousLoggerConfig != null) {
                configuration.addLogger(loggerName, previousLoggerConfig);
            }
            configuration.removeAppender(appender.getName());
            loggerContext.updateLoggers();
            appender.stop();
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

    private static final class TestAppender extends AbstractAppender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        private TestAppender(String name) {
            super(name, null, PatternLayout.newBuilder().withPattern("%msg").build(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private List<String> getMessages() {
            return messages;
        }
    }
}
