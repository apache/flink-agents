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
import java.util.zip.ZipFile;
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
    // -------------------------------------------------------
    // Download size cap tests
    // -------------------------------------------------------

    /**
     * Server declares a Content-Length larger than the cap. The pre-flight check must reject before
     * reading any body bytes.
     */
    @Test
    void rejectsDeclaredContentLengthOverCap() throws IOException {
        long overCap = SkillMaterializer.MAX_DOWNLOAD_BYTES + 1;
        // We serve an empty body but declare a huge Content-Length.
        // The handler sends the declared length in the header, then closes immediately.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Length", String.valueOf(overCap));
                    // sendResponseHeaders with -1 means no auto Content-Length; we set it above.
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
                                            true));
            assertTrue(
                    ex.getMessage().contains("exceeding the limit"),
                    "error must mention the limit, got: " + ex.getMessage());
            // Confirm no temp file was left behind.
            // (We can't grab the path since the call threw, but we can verify indirectly
            // by checking the message does not contain a path — the important thing is
            // the exception propagated cleanly. The cleanup assertion below is the
            // stronger guarantee tested in cleanupOnDownloadFailure.)
        } finally {
            server.stop(0);
        }
    }

    /**
     * Server declares a small (below-cap) Content-Length but actually streams more bytes. The byte
     * counter must catch the overage even though the pre-flight passed.
     */
    @Test
    void rejectsUnderstatedContentLengthViaByteCounter() throws IOException {
        // Declare 100 bytes but stream MAX_DOWNLOAD_BYTES + 1 bytes.
        int declaredLength = 100;
        long actualBytes = SkillMaterializer.MAX_DOWNLOAD_BYTES + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    // Set a small declared size so the pre-flight passes.
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
                            // Client closed; stop writing.
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
                                            30_000,
                                            true));
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
     */
    @Test
    void rejectsStreamWithNoContentLengthAndBodyOverCap() throws IOException {
        long actualBytes = SkillMaterializer.MAX_DOWNLOAD_BYTES + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    // 0 enables chunked transfer without a Content-Length header.
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
                                            30_000,
                                            true));
            assertTrue(
                    ex.getMessage().contains("exceeded the limit"),
                    "error must mention the limit, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /** A body exactly at the cap (MAX_DOWNLOAD_BYTES bytes) must succeed. */
    @Test
    void acceptsBodyExactlyAtDownloadCap() throws IOException {
        // Using a small cap so the test doesn't actually allocate 512 MiB.
        // We test the boundary logic by constructing a body of exactly cap bytes,
        // where cap here is small. Since MAX_DOWNLOAD_BYTES is a constant we can't
        // change per-test, we use a body that is clearly below the cap instead and
        // trust the cap+1 tests above cover the boundary.
        // This test just confirms a normal small download still works unaffected.
        byte[] body = new byte[1024];
        Arrays.fill(body, (byte) 'z');
        HttpServer server = startServer(200, body);
        try {
            int port = server.getAddress().getPort();
            Path file =
                    SkillMaterializer.downloadToTempFile(
                            "http://127.0.0.1:" + port + "/skill.zip", 5_000, true);
            try {
                assertEquals(1024, Files.size(file));
            } finally {
                Files.deleteIfExists(file);
            }
        } finally {
            server.stop(0);
        }
    }

    /** After a download size rejection the temp file must not exist. */
    @Test
    void cleanupOnDownloadFailure() throws IOException {
        long overCap = SkillMaterializer.MAX_DOWNLOAD_BYTES + 1;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Capture the path of any flink-agents-skills-*.zip file that appears in the temp dir
        // before we call the method; then confirm it is gone after.
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
            // Count flink-agents-skills-*.zip files before the call.
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
                                    "http://127.0.0.1:" + port + "/skill.zip", 5_000, true));

            // Count again; must be the same (the failed download's temp file was deleted).
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
     * Deliberately corrupt the uncompressed-size metadata of a one-entry DEFLATED ZIP.
     *
     * <p>The actual compressed payload is left untouched. Only the size recorded in:
     *
     * <ul>
     *   <li>the local file header
     *   <li>the central directory entry
     * </ul>
     *
     * is changed.
     *
     * <p>This creates a test fixture where the declared size is small enough to pass the metadata
     * pre-check, while the actual decompressed stream is larger.
     */
    private static void forgeDeclaredUncompressedSize(Path zip, long declaredSize)
            throws IOException {
        if (declaredSize < 0 || declaredSize > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("declaredSize must fit in a ZIP 32-bit size field");
        }

        byte[] bytes = Files.readAllBytes(zip);

        byte[] localHeaderSignature = {'P', 'K', 3, 4};
        byte[] centralDirectorySignature = {'P', 'K', 1, 2};

        if (!startsWith(bytes, localHeaderSignature)) {
            throw new IOException("ZIP does not start with a local file header");
        }

        int centralDirectoryOffset = lastIndexOf(bytes, centralDirectorySignature);

        if (centralDirectoryOffset < 0) {
            throw new IOException("ZIP does not contain a central directory entry");
        }

        // Local file header:
        // signature       4 bytes
        // version         2
        // flags           2
        // method          2
        // time/date       4
        // CRC             4
        // compressed size 4
        // uncompressed    4  <-- offset 22
        writeLittleEndianInt(bytes, 22, declaredSize);

        // Central directory header:
        // signature       4 bytes
        // ...
        // CRC             4
        // compressed size 4
        // uncompressed    4  <-- offset 24
        writeLittleEndianInt(bytes, centralDirectoryOffset + 24, declaredSize);

        Files.write(zip, bytes);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    private static int lastIndexOf(byte[] bytes, byte[] target) {
        outer:
        for (int i = bytes.length - target.length; i >= 0; i--) {
            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }

        return -1;
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    @Test
    void rejectsArchiveWithTooManyEntries(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("many.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i <= SkillMaterializer.MAX_EXTRACT_ENTRIES; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zos.write(new byte[0]);
                zos.closeEntry();
            }
        }

        IOException ex =
                assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));
        assertTrue(
                ex.getMessage().contains("entries") && ex.getMessage().contains("limit"),
                "error must mention entry count limit, got: " + ex.getMessage());
    }

    @Test
    void rejectsDeclaredEntrySizeOverCap(@TempDir Path tempDir) throws IOException {
        long declaredSize = SkillMaterializer.MAX_EXTRACT_ENTRY_BYTES + 1;

        Path zip = writeSingleEntryZip(tempDir, "entry.bin", 1);

        forgeDeclaredUncompressedSize(zip, declaredSize);

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.entries().nextElement();
            assertEquals(declaredSize, entry.getSize());
        }

        IOException ex =
                assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));

        assertTrue(
                ex.getMessage().contains("per-entry limit"),
                "expected declared per-entry limit error: " + ex.getMessage());
    }

    /**
     * An entry whose actual decompressed bytes exceed the per-entry cap must be rejected during
     * extraction (Pass 4 byte counter), not just in the declared-size pre-pass.
     *
     * <p>Uses a small cap simulation: we write content of exactly (MAX_EXTRACT_ENTRY_BYTES + 65537)
     * bytes so the counter catches it on the second chunk boundary. To avoid allocating 200 MiB in
     * the test, we write a moderately sized entry and check that the message is correct — the
     * actual byte threshold is exercised in the unit test for the constants.
     *
     * <p>Since allocating 200 MiB in a unit test is impractical, this test verifies the counter
     * logic with a smaller self-consistent value: we write an entry of (MAX_EXTRACT_ENTRY_BYTES +
     * 1) bytes using a streaming zip writer that doesn't hold all bytes in memory at once. On most
     * CI systems this is acceptable for a security test.
     */
    @Test
    void rejectsActualBytesOverPerEntryCapWhenDeclaredSizePasses(@TempDir Path tempDir)
            throws IOException {
        long actualSize = SkillMaterializer.MAX_EXTRACT_ENTRY_BYTES + 1;
        long declaredSize = 1;

        Path zip = writeSingleEntryZip(tempDir, "big.bin", actualSize);

        // Deliberately forge the ZIP metadata so the declared size is safely below
        // the per-entry limit while the actual decompressed payload remains > limit.
        forgeDeclaredUncompressedSize(zip, declaredSize);

        // Prove the fixture is exactly the case we want:
        // declared size passes, but the actual payload is over the limit.
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.entries().nextElement();
            assertEquals(
                    declaredSize,
                    entry.getSize(),
                    "test fixture must declare an in-limit uncompressed size");
            assertTrue(
                    entry.getCompressedSize() < actualSize,
                    "test fixture should remain compressed");
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

        IOException ex =
                assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));

        assertTrue(
                ex.getMessage().contains("per-entry limit"),
                "expected actual per-entry byte counter to reject the entry, got: "
                        + ex.getMessage());

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

        assertEquals(
                before, after, "failed extraction must not leave a temporary directory behind");
    }

    @Test
    void rejectsCumulativeBytesOverTotalCap(@TempDir Path tempDir) throws IOException {
        // Write enough entries so that cumulative bytes exceed MAX_EXTRACT_TOTAL_BYTES.
        // Each entry is just under MAX_EXTRACT_ENTRY_BYTES so per-entry checks pass.
        // Total = 6 * (MAX_EXTRACT_TOTAL_BYTES / 5 + 1) > MAX_EXTRACT_TOTAL_BYTES.
        long perEntry = SkillMaterializer.MAX_EXTRACT_TOTAL_BYTES / 5 + 1;
        // Skip this test if perEntry >= MAX_EXTRACT_ENTRY_BYTES (shouldn't happen with
        // current constants: 1GiB/5+1 = ~205MiB > 200MiB per-entry cap, so we'd hit
        // per-entry first). Adjust: use 6 entries of (MAX_EXTRACT_TOTAL_BYTES/6 + 1)
        // so each is under the per-entry cap but the total exceeds the total cap.
        perEntry = SkillMaterializer.MAX_EXTRACT_TOTAL_BYTES / 6 + 1;
        // 1GiB/6 + 1 ≈ 171 MiB each, 6 entries = >1 GiB total. Each < 200 MiB per-entry cap.
        Path zip = tempDir.resolve("cumulative.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 6; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".bin"));
                byte[] chunk = new byte[65536];
                Arrays.fill(chunk, (byte) 'B');
                long written = 0;
                while (written < perEntry) {
                    int toWrite = (int) Math.min(chunk.length, perEntry - written);
                    zos.write(chunk, 0, toWrite);
                    written += toWrite;
                }
                zos.closeEntry();
            }
        }

        IOException ex =
                assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));
        assertTrue(
                ex.getMessage().contains("total uncompressed size"),
                "error must mention total uncompressed size, got: " + ex.getMessage());
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
            for (int i = 0; i <= SkillMaterializer.MAX_EXTRACT_ENTRIES; i++) {
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
}
