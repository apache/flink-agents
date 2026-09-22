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
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.plan.AgentConfiguration;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
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
    void rejectsScopedIpv6BeforeConnection() {
        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                SkillMaterializer.downloadToTempFile(
                                        "https://[fe80::1%25lo0]/skills.zip", 5_000));
        System.out.println("Exception message: " + ex.getMessage());
        System.out.println("Cause: " + ex.getCause());
        assertTrue(ex.getMessage().contains("must not include an IPv6 zone identifier"));
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
    // -------------------------------------------------------
    // Download size cap tests
    // -------------------------------------------------------

    /**
     * Server declares a Content-Length larger than the injected cap. The pre-flight check must
     * reject before reading any body bytes.
     */
    @Test
    void rejectsDeclaredContentLengthOverCap() throws IOException {
        long cap = 1024L;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(cap, cap * 10, cap * 100, 1_000);

        long overCap = cap + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Length", String.valueOf(overCap));
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            "http://127.0.0.1:" + port + "/skill.zip",
                                            5_000,
                                            true,
                                            limits));
            assertTrue(
                    ex.getMessage().contains("exceeding the limit"),
                    "error must mention the limit, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Server declares a small Content-Length but actually streams more bytes. The byte counter must
     * catch the overage even though the pre-flight check passed.
     *
     * <p>Uses a 1 KiB injectable cap so the test streams only 1,025 bytes instead of 512 MiB + 1.
     */
    @Test
    void rejectsUnderstatedContentLengthViaByteCounter() throws IOException {
        long cap = 1024L;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(cap, cap * 10, cap * 100, 1_000);

        int declaredLength = 100;
        long actualBytes = cap + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add("Content-Length", String.valueOf(declaredLength));
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream body = exchange.getResponseBody();
                    byte[] chunk = new byte[65536];
                    Arrays.fill(chunk, (byte) 'x');
                    long remaining = actualBytes;
                    while (remaining > 0) {
                        int toWrite = (int) Math.min(chunk.length, remaining);
                        try {
                            body.write(chunk, 0, toWrite);
                            body.flush();
                        } catch (IOException ignored) {
                            break;
                        }
                        remaining -= toWrite;
                    }
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            "http://127.0.0.1:" + port + "/skill.zip",
                                            5_000,
                                            true,
                                            limits));
            assertTrue(
                    ex.getMessage().contains("exceeded the limit"),
                    "error must mention the limit, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Server streams past the cap with no Content-Length header at all. The byte counter must catch
     * it.
     *
     * <p>Uses a 1 KiB injectable cap so the test streams only 1,025 bytes instead of 512 MiB + 1.
     */
    @Test
    void rejectsStreamWithNoContentLengthAndBodyOverCap() throws IOException {
        long cap = 1024L;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(cap, cap * 10, cap * 100, 1_000);

        long actualBytes = cap + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream body = exchange.getResponseBody();
                    byte[] chunk = new byte[65536];
                    Arrays.fill(chunk, (byte) 'x');
                    long remaining = actualBytes;
                    while (remaining > 0) {
                        int toWrite = (int) Math.min(chunk.length, remaining);
                        try {
                            body.write(chunk, 0, toWrite);
                            body.flush();
                        } catch (IOException ignored) {
                            break;
                        }
                        remaining -= toWrite;
                    }
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            "http://127.0.0.1:" + port + "/skill.zip",
                                            5_000,
                                            true,
                                            limits));
            assertTrue(
                    ex.getMessage().contains("exceeded the limit"),
                    "error must mention the limit, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A body of exactly {@code cap} bytes must succeed (boundary is inclusive). Uses a 1 KiB
     * injectable cap so the test does not allocate 512 MiB.
     */
    @Test
    void acceptsBodyExactlyAtDownloadCap() throws IOException {
        long cap = 1024L;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(cap, cap * 10, cap * 100, 1_000);

        byte[] body = new byte[(int) cap];
        Arrays.fill(body, (byte) 'z');
        HttpServer server = startServer(200, body);
        try {
            int port = server.getAddress().getPort();
            Path file =
                    SkillMaterializer.downloadToTempFile(
                            "http://127.0.0.1:" + port + "/skill.zip", 5_000, true, limits);
            try {
                assertEquals(cap, Files.size(file));
            } finally {
                Files.deleteIfExists(file);
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * A body of {@code cap + 1} bytes must be rejected. Paired with {@link
     * #acceptsBodyExactlyAtDownloadCap()} to prove the boundary is enforced at exactly the right
     * byte.
     *
     * <p>The server intentionally omits {@code Content-Length} so the pre-flight check cannot fire;
     * only the streaming counter can reject the download. this isolates the counter under test.
     */
    @Test
    void rejectsBodyOneByteOverDownloadCap() throws IOException {
        long cap = 1024L;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(cap, cap * 10, cap * 100, 1_000);

        long actualBytes = cap + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    // sendResponseHeaders with 0 = chunked / no Content-Length header,
                    // so only the streaming byte counter can reject the download.
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    byte[] chunk = new byte[(int) actualBytes];
                    Arrays.fill(chunk, (byte) 'z');
                    try {
                        out.write(chunk);
                        out.flush();
                    } catch (IOException ignored) {
                        // client closed early after limit hit - expected
                    }
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    SkillMaterializer.downloadToTempFile(
                                            "http://127.0.0.1:" + port + "/skill.zip",
                                            5_000,
                                            true,
                                            limits));
            assertTrue(
                    ex.getMessage().contains("exceeded the limit"),
                    "error must mention the limit, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /** After a download size rejection the temp file must not exist. */
    @Test
    void cleanupOnDownloadFailure() throws IOException {
        long cap = 1024L;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(cap, cap * 10, cap * 100, 1_000);

        long overCap = cap + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));

        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Length", String.valueOf(overCap));
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            long before;
            try (Stream<Path> ls = Files.list(tmpDir)) {
                before =
                        ls.filter(
                                        p ->
                                                p.getFileName()
                                                                .toString()
                                                                .startsWith("flink-agents-skills-")
                                                        && p.getFileName()
                                                                .toString()
                                                                .endsWith(".zip"))
                                .count();
            }

            assertThrows(
                    IOException.class,
                    () ->
                            SkillMaterializer.downloadToTempFile(
                                    "http://127.0.0.1:" + port + "/skill.zip",
                                    5_000,
                                    true,
                                    limits));

            long after;
            try (Stream<Path> ls = Files.list(tmpDir)) {
                after =
                        ls.filter(
                                        p ->
                                                p.getFileName()
                                                                .toString()
                                                                .startsWith("flink-agents-skills-")
                                                        && p.getFileName()
                                                                .toString()
                                                                .endsWith(".zip"))
                                .count();
            }
            assertEquals(before, after, "failed download must not leave a temp file behind");
        } finally {
            server.stop(0);
        }
    }

    // -------------------------------------------------------
    // Extraction size cap tests
    // -------------------------------------------------------

    /** Helper: write a zip where one entry has the given number of bytes of content. */
    private static Path writeSingleEntryZip(Path dir, String entryName, long entryBytes)
            throws IOException {
        Path zip = dir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] chunk = new byte[65536];
            Arrays.fill(chunk, (byte) 'x');
            long remaining = entryBytes;
            while (remaining > 0) {
                int toWrite = (int) Math.min(chunk.length, remaining);
                zos.write(chunk, 0, toWrite);
                remaining -= toWrite;
            }
            zos.closeEntry();
        }
        return zip;
    }

    /**
     * Patch the declared uncompressed size in both the local file header (LFH) and central
     * directory header (CDH) for every entry.
     */
    private static void forgeDeclaredSizesForAllEntries(Path zip, long declaredSizePerEntry)
            throws IOException {
        if (declaredSizePerEntry < 0 || declaredSizePerEntry > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(
                    "declaredSizePerEntry must fit in a ZIP 32-bit field");
        }

        byte[] bytes = Files.readAllBytes(zip);
        // Patch LFH entries
        byte[] lfhSig = {'P', 'K', 3, 4};
        int pos = 0;
        while (pos <= bytes.length - 30) {
            if (bytes[pos] == lfhSig[0]
                    && bytes[pos + 1] == lfhSig[1]
                    && bytes[pos + 2] == lfhSig[2]
                    && bytes[pos + 3] == lfhSig[3]) {
                writeLittleEndianInt(bytes, pos + 22, declaredSizePerEntry);
                int filenameLen = readLittleEndianShort(bytes, pos + 26);
                int extraLen = readLittleEndianShort(bytes, pos + 28);
                pos += 30 + filenameLen + extraLen;
            } else {
                pos++;
            }
        }
        // Patch CDH entries
        byte[] cdhSig = {'P', 'K', 1, 2};
        pos = 0;
        while (pos <= bytes.length - 46) {
            if (bytes[pos] == cdhSig[0]
                    && bytes[pos + 1] == cdhSig[1]
                    && bytes[pos + 2] == cdhSig[2]
                    && bytes[pos + 3] == cdhSig[3]) {
                writeLittleEndianInt(bytes, pos + 24, declaredSizePerEntry);
                int filenameLen = readLittleEndianShort(bytes, pos + 28);
                int extraLen = readLittleEndianShort(bytes, pos + 30);
                int commentLen = readLittleEndianShort(bytes, pos + 32);
                pos += 46 + filenameLen + extraLen + commentLen;
            } else {
                pos++;
            }
        }
        Files.write(zip, bytes);
    }

    private static long readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static int readLittleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    @Test
    void rejectsArchiveWithTooManyEntries(@TempDir Path tempDir) throws IOException {
        SkillMaterializer.Limits limits = new SkillMaterializer.Limits(1024L, 1024L, 10_000L, 2);

        Path zip = tempDir.resolve("many.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 3; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zos.write(new byte[0]);
                zos.closeEntry();
            }
        }

        IOException ex =
                assertThrows(
                        IOException.class, () -> SkillMaterializer.extractZipSafely(zip, limits));
        assertTrue(
                ex.getMessage().contains("entries") && ex.getMessage().contains("limit"),
                "error must mention entry count limit, got: " + ex.getMessage());
    }

    @Test
    void rejectsDeclaredEntrySizeOverCap(@TempDir Path tempDir) throws IOException {
        long cap = 400L;
        SkillMaterializer.Limits limits = new SkillMaterializer.Limits(1024L, cap, 4_000L, 1_000);

        Path zip = writeSingleEntryZip(tempDir, "entry.bin", 1);
        forgeDeclaredSizesForAllEntries(zip, cap + 1);

        IOException ex =
                assertThrows(
                        IOException.class, () -> SkillMaterializer.extractZipSafely(zip, limits));
        assertTrue(
                ex.getMessage().contains("per-entry limit"),
                "expected declared per-entry limit error: " + ex.getMessage());
    }

    @Test
    void rejectsActualBytesOverPerEntryCapWhenDeclaredSizePasses(@TempDir Path tempDir)
            throws IOException {
        int actualSize = 512;
        int cap = 400;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(1024L, cap, cap * 10L, 1_000);

        writeSingleEntryZip(tempDir, "large.bin", actualSize);
        Path zip = tempDir.resolve("test.zip");
        forgeDeclaredSizesForAllEntries(zip, 1L);

        IOException ex =
                assertThrows(
                        IOException.class, () -> SkillMaterializer.extractZipSafely(zip, limits));
        assertTrue(
                ex.getMessage().contains("per-entry limit"),
                "expected actual per-entry byte counter to reject the entry, got: "
                        + ex.getMessage());
    }

    @Test
    void tamperedDeclaredSizeBelowActualExtractsSuccessfully(@TempDir Path tempDir)
            throws IOException {
        int actualSize = 512;
        writeSingleEntryZip(tempDir, "large.bin", actualSize);
        Path zip = tempDir.resolve("test.zip");
        // Declared size is forged below actual size in both ZIP headers.
        forgeDeclaredSizesForAllEntries(zip, 1L);

        try (SkillMaterializer.Materialized m = SkillMaterializer.extractZipSafely(zip)) {
            Path extracted = m.getDir().resolve("large.bin");
            assertEquals(actualSize, Files.size(extracted));
        }
    }

    @Test
    void tamperedDeclaredEntrySizeStillCleansUp(@TempDir Path tempDir) throws IOException {
        int actualSize = 512;
        int cap = 400;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(1024L, cap, cap * 10L, 1_000);

        writeSingleEntryZip(tempDir, "large.bin", actualSize);
        Path zip = tempDir.resolve("test.zip");
        forgeDeclaredSizesForAllEntries(zip, 1L);

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (Stream<Path> ls = Files.list(tmpDir)) {
            before =
                    ls.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("flink-agents-skills-")
                                                    && Files.isDirectory(p))
                            .count();
        }

        assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip, limits));

        long after;
        try (Stream<Path> ls = Files.list(tmpDir)) {
            after =
                    ls.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("flink-agents-skills-")
                                                    && Files.isDirectory(p))
                            .count();
        }
        assertEquals(before, after, "failed extraction must not leave a temp dir behind");
    }

    /**
     * Two entries of 600 bytes each fit individually under the per-entry cap (700 bytes) but
     * together exceed the total cap (1,024 bytes). The forged declared sizes make the metadata
     * pre-check pass, so only the actual streaming byte counter rejects the archive.
     */
    @Test
    void rejectsCumulativeBytesOverTotalCap(@TempDir Path tempDir) throws IOException {
        int entryBytes = 600;
        SkillMaterializer.Limits limits =
                new SkillMaterializer.Limits(512L * 1024 * 1024, 700L, 1_024L, 1_000);

        Path zip = tempDir.resolve("cumulative.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 2; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".bin"));
                byte[] content = new byte[entryBytes];
                Arrays.fill(content, (byte) 'B');
                zos.write(content);
                zos.closeEntry();
            }
        }

        forgeDeclaredSizesForAllEntries(zip, 1L);

        IOException ex =
                assertThrows(
                        IOException.class, () -> SkillMaterializer.extractZipSafely(zip, limits));
        assertTrue(
                ex.getMessage().contains("total extracted size"),
                "byte counter must reject cumulative total, got: " + ex.getMessage());
    }

    /**
     * After an extraction size rejection, the extraction directory must not exist. Proves eager
     * cleanup on failure.
     */
    @Test
    void cleanupOnExtractionFailure(@TempDir Path tempDir) throws IOException {
        // Write an archive with too many entries to trigger failure cheaply.
        Path zip = tempDir.resolve("many.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i <= SkillMaterializer.Limits.DEFAULT.maxExtractEntries; i++) {
                zos.putNextEntry(new ZipEntry("e" + i + ".txt"));
                zos.write(new byte[0]);
                zos.closeEntry();
            }
        }

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (Stream<Path> ls = Files.list(tmpDir)) {
            before =
                    ls.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("flink-agents-skills-")
                                                    && Files.isDirectory(p))
                            .count();
        }

        assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));

        long after;
        try (Stream<Path> ls = Files.list(tmpDir)) {
            after =
                    ls.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("flink-agents-skills-")
                                                    && Files.isDirectory(p))
                            .count();
        }
        assertEquals(before, after, "failed extraction must not leave a temp dir behind");
    }

    /**
     * A zip-slip entry must still be caught and the extraction directory must be cleaned up, even
     * though zip-slip validation (Pass 1) runs before size caps (Pass 3/4). Verifies
     * cleanup-on-failure covers the zip-slip path too.
     */
    @Test
    void cleanupOnZipSlipFailure(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("slip.zip");
        writeZip(zip, Map.of("../escape.txt", "pwn"));

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (Stream<Path> ls = Files.list(tmpDir)) {
            before =
                    ls.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("flink-agents-skills-")
                                                    && Files.isDirectory(p))
                            .count();
        }

        assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));

        long after;
        try (Stream<Path> ls = Files.list(tmpDir)) {
            after =
                    ls.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("flink-agents-skills-")
                                                    && Files.isDirectory(p))
                            .count();
        }
        assertEquals(before, after, "zip-slip failure must not leave a temp dir behind");
    }

    /**
     * A valid archive must still extract correctly — no regressions. Reuses the existing
     * extractsTopLevelEntries test logic but explicitly confirms the new passes don't break the
     * happy path.
     */
    @Test
    void happyPathExtractionUnchanged(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("ok.zip");
        writeZip(
                zip,
                Map.of(
                        "skill-a/SKILL.md", "---\nname: skill-a\n---\nbody",
                        "skill-b/SKILL.md", "---\nname: skill-b\n---\nbody"));

        try (SkillMaterializer.Materialized m = SkillMaterializer.extractZipSafely(zip)) {
            assertTrue(Files.isRegularFile(m.getDir().resolve("skill-a/SKILL.md")));
            assertTrue(Files.isRegularFile(m.getDir().resolve("skill-b/SKILL.md")));
        }
    }

    /**
     * {@link SkillMaterializer.Limits#fromConfig} must read all four YAML keys from a real {@link
     * org.apache.flink.agents.api.configuration.ReadableConfiguration} and produce a {@code Limits}
     * whose fields match the configured values. This test validates the YAML key strings in {@link
     * AgentConfigOptions} are correct end-to-end — a typo in any key would cause {@code fromConfig}
     * to silently fall back to the default value and this assertion would fail.
     */
    @Test
    void limitsFromConfigReadsAllFourKeys() {
        AgentConfiguration cfg = new AgentConfiguration();
        cfg.set(AgentConfigOptions.SKILL_SOURCE_URL_MAX_DOWNLOAD_BYTES, 11L);
        cfg.set(AgentConfigOptions.SKILL_SOURCE_URL_MAX_EXTRACT_ENTRY_BYTES, 22L);
        cfg.set(AgentConfigOptions.SKILL_SOURCE_URL_MAX_EXTRACT_TOTAL_BYTES, 33L);
        cfg.set(AgentConfigOptions.SKILL_SOURCE_URL_MAX_EXTRACT_ENTRIES, 44);

        SkillMaterializer.Limits limits = SkillMaterializer.Limits.fromConfig(cfg);

        assertEquals(
                11L,
                limits.maxDownloadBytes,
                "fromConfig must read skill.source.url.max-download-bytes");
        assertEquals(
                22L,
                limits.maxExtractEntryBytes,
                "fromConfig must read skill.source.url.max-extract-entry-bytes");
        assertEquals(
                33L,
                limits.maxExtractTotalBytes,
                "fromConfig must read skill.source.url.max-extract-total-bytes");
        assertEquals(
                44,
                limits.maxExtractEntries,
                "fromConfig must read skill.source.url.max-extract-entries");
    }

    /**
     * {@link SkillMaterializer.Limits#fromConfig} with a {@code null} config must return {@link
     * SkillMaterializer.Limits#DEFAULT} without throwing.
     */
    @Test
    void limitsFromConfigNullReturnsDefault() {
        SkillMaterializer.Limits limits = SkillMaterializer.Limits.fromConfig(null);
        assertEquals(SkillMaterializer.Limits.DEFAULT.maxDownloadBytes, limits.maxDownloadBytes);
        assertEquals(
                SkillMaterializer.Limits.DEFAULT.maxExtractEntryBytes, limits.maxExtractEntryBytes);
        assertEquals(
                SkillMaterializer.Limits.DEFAULT.maxExtractTotalBytes, limits.maxExtractTotalBytes);
        assertEquals(SkillMaterializer.Limits.DEFAULT.maxExtractEntries, limits.maxExtractEntries);
    }

    /**
     * A zip entry whose name contains a NUL byte ({@code \u0000}) triggers an {@link
     * java.nio.file.InvalidPathException} inside {@link java.nio.file.Path#resolve}. {@link
     * SkillMaterializer#extractZipSafely} must wrap this as an {@link IOException} (so callers see
     * a consistent checked exception) while preserving the original cause.
     */
    @Test
    void rejectsNulByteInZipEntryName(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("nul.zip");
        // Write a zip with a NUL byte in the entry name. ZipOutputStream accepts it;
        // Path.resolve() will reject it with InvalidPathException on extraction.
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("evil\u0000.txt"));
            zos.write("pwn".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        IOException ex =
                assertThrows(
                        IOException.class,
                        () -> SkillMaterializer.extractZipSafely(zip),
                        "NUL byte in entry name must throw IOException");
        // The original InvalidPathException must be preserved as the cause so
        // diagnostics are not lost.
        assertTrue(
                ex.getCause() instanceof java.nio.file.InvalidPathException,
                "cause must be InvalidPathException, got: " + ex.getCause());
    }
}
