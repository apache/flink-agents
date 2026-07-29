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

package org.apache.flink.agents.runtime.skill.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Internal helpers for materializing skill sources (zip files, URL downloads, classpath JAR
 * entries) into a local temp directory. Each materialization is returned as a {@link Materialized}
 * handle that owns the temp dir and a JVM shutdown hook; callers must {@code close()} the handle to
 * release the dir eagerly (the hook is the fallback, executed only if {@code close()} is never
 * called before the JVM exits).
 */
public final class SkillMaterializer {

    private static final Logger LOG = LoggerFactory.getLogger(SkillMaterializer.class);

    private static final String TEMP_DIR_PREFIX = "flink-agents-skills-";

    private static final int JAR_URL_PREFIX_LEN = "jar:".length();

    private SkillMaterializer() {}

    /**
     * Owns one materialized temp directory plus the shutdown hook registered for its fallback
     * cleanup. {@link #close()} deregisters the hook and deletes the dir immediately; it is
     * idempotent and tolerates a JVM already in shutdown.
     */
    public static final class Materialized implements AutoCloseable {
        private final Path dir;
        @javax.annotation.Nullable private final Thread hook;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Materialized(Path dir, @javax.annotation.Nullable Thread hook) {
            this.dir = dir;
            this.hook = hook;
        }

        /**
         * Wrap an existing directory the caller does not own (e.g. a classpath directory on disk).
         * {@link #close()} on a borrowed handle does nothing.
         */
        public static Materialized borrowed(Path existingDir) {
            return new Materialized(existingDir, null);
        }

        public Path getDir() {
            return dir;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (hook == null) {
                // Borrowed: nothing to release.
                return;
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down; the hook will fire normally.
            }
            deleteRecursively(dir);
        }
    }

    /**
     * Register a JVM shutdown hook that removes {@code path} recursively, and return the hook
     * thread so the caller can deregister it. Failures during deletion are silently ignored
     * (best-effort cleanup).
     */
    private static Thread registerCleanup(Path path) {
        Thread hook =
                new Thread(() -> deleteRecursively(path), "skill-cleanup-" + path.getFileName());
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /**
     * Extract a zip into a fresh temp directory and return a {@link Materialized} handle owning
     * that directory. Validates every entry against zip-slip (paths must resolve inside the
     * extraction directory). Registers a JVM shutdown hook as fallback cleanup; callers should
     * {@link Materialized#close()} the handle to free the dir eagerly.
     *
     * @throws IOException if any zip entry resolves outside the extraction directory.
     */
    public static Materialized extractZipSafely(Path zipPath) throws IOException {
        Path extractDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
        // Register cleanup before validation so the empty tempdir is always reclaimed,
        // even if validation raises.
        Thread hook = registerCleanup(extractDir);
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = extractDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(extractDir)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, target);
                    }
                }
            }
        }
        return new Materialized(extractDir, hook);
    }

    /**
     * Extract every JAR entry whose name starts with {@code resourcePrefix + "/"} into a fresh temp
     * directory. The prefix itself is stripped (so entries under {@code skills/skill-a/...} extract
     * as {@code skill-a/...}).
     *
     * <p>Registers a JVM shutdown hook for cleanup. Rejects entries that would resolve outside the
     * extraction directory (zip-slip).
     */
    public static Materialized extractClasspathFromJar(URL jarUrl, String resourcePrefix)
            throws IOException {
        return extractClasspathFromJars(List.of(jarUrl), resourcePrefix);
    }

    /**
     * Extract every JAR entry whose name starts with {@code resourcePrefix + "/"} from
     * <em>each</em> of {@code jarUrls} into a single fresh temp directory, merging the results. The
     * prefix is stripped from entry names.
     *
     * <p>On collisions (same relative path in two jars) the later jar wins and a WARN is logged.
     * Rejects entries that would resolve outside the extraction directory (zip-slip). Registers a
     * single JVM shutdown hook for the merged temp directory (avoiding the per-jar hook
     * accumulation pattern fixed under review #10).
     */
    public static Materialized extractClasspathFromJars(List<URL> jarUrls, String resourcePrefix)
            throws IOException {
        if (jarUrls == null || jarUrls.isEmpty()) {
            throw new IllegalArgumentException("jarUrls must be non-empty");
        }
        Path extractDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
        Thread hook = registerCleanup(extractDir);
        String prefix = resourcePrefix.endsWith("/") ? resourcePrefix : resourcePrefix + "/";
        for (URL jarUrl : jarUrls) {
            copyJarEntries(jarUrl, prefix, extractDir);
        }
        return new Materialized(extractDir, hook);
    }

    /**
     * Open the jar referenced by {@code jarUrl} and copy every entry whose name starts with {@code
     * prefix} into {@code extractDir}, stripping the prefix. Collisions WARN and overwrite.
     */
    private static void copyJarEntries(URL jarUrl, String prefix, Path extractDir)
            throws IOException {
        // Parse the JAR file URL from the jar: URL. The format is jar:<jar-file-url>!/[entry].
        // We extract just the inner jar-file URL so we can open the whole JarFile and enumerate
        // all entries — JarURLConnection.getJarFile() would fail when the entry specifier names a
        // prefix that has no corresponding stored directory entry.
        String spec = jarUrl.toString();
        int sep = spec.indexOf("!/");
        String innerSpec =
                sep >= 0
                        ? spec.substring(JAR_URL_PREFIX_LEN, sep)
                        : spec.substring(JAR_URL_PREFIX_LEN);
        URL innerUrl = new URL(innerSpec);
        File jarFileObj;
        try {
            jarFileObj = new File(innerUrl.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            // IllegalArgumentException is thrown by File(URI) when the URI scheme is not "file"
            // (e.g. a JAR nested behind http://). Surface both as IOException so callers that
            // catch IOException for graceful failure handling see them.
            throw new IOException("Invalid JAR URL: " + jarUrl, e);
        }
        try (JarFile jarFile = new JarFile(jarFileObj)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix)) {
                    continue;
                }
                String rel = entry.getName().substring(prefix.length());
                if (rel.isEmpty()) {
                    continue;
                }
                Path target = extractDir.resolve(rel).normalize();
                if (!target.startsWith(extractDir)) {
                    throw new IOException("Unsafe jar entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    if (Files.exists(target)) {
                        LOG.warn(
                                "Classpath entry {} from {} overwrites a previously merged entry"
                                        + " at the same relative path; last-write-wins.",
                                entry.getName(),
                                jarUrl);
                    }
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * Download {@code url} to a temp file with the {@code .zip} suffix and return its path.
     *
     * <p>The {@code .zip} suffix is load-bearing: {@link FileSystemSkillRepository} uses {@code
     * path.endsWith(".zip")} to detect zip input. Do not change it.
     *
     * @throws IOException on connect / read failures or HTTP error responses.
     */
    public static Path downloadToTempFile(String url, int timeoutMs) throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("GET");
        Path tmpZip = Files.createTempFile(TEMP_DIR_PREFIX, ".zip");
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tmpZip, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmpZip);
            throw e;
        } finally {
            conn.disconnect();
        }
        return tmpZip;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException ignored) {
                                    // Cleanup is best-effort.
                                }
                            });
        } catch (IOException ignored) {
            // Cleanup is best-effort.
        }
    }
}
