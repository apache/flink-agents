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

import org.apache.flink.agents.api.skills.SkillUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
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

    private static final int MAX_REDIRECTS = 10;

    private static final int JAR_URL_PREFIX_LEN = "jar:".length();

    // --- Size caps for download and extraction (issue #1072) ---

    /** Maximum number of bytes accepted from a single HTTP download. */
    public static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024; // 512 MiB

    /**
     * Maximum uncompressed size of any single entry during zip extraction. Declared sizes in the
     * zip central directory are attacker-controlled; this cap is enforced against actual bytes
     * written, not {@link ZipEntry#getSize()}.
     */
    public static final long MAX_EXTRACT_ENTRY_BYTES = 200L * 1024 * 1024; // 200 MiB

    /**
     * Maximum cumulative uncompressed bytes written across all entries during a single zip
     * extraction. Enforced against actual bytes written.
     */
    public static final long MAX_EXTRACT_TOTAL_BYTES = 1024L * 1024 * 1024; // 1 GiB

    /** Maximum number of entries permitted in a single zip archive. */
    public static final int MAX_EXTRACT_ENTRIES = 10_000;

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
     * that directory.
     *
     * <p>Security properties:
     *
     * <ul>
     *   <li>Validates every entry against zip-slip before any extraction begins.
     *   <li>Rejects archives with more than {@link #MAX_EXTRACT_ENTRIES} entries.
     *   <li>Enforces {@link #MAX_EXTRACT_ENTRY_BYTES} per entry and {@link
     *       #MAX_EXTRACT_TOTAL_BYTES} cumulatively, measured against actual decompressed bytes
     *       written — not against the declared sizes in the zip central directory, which are
     *       attacker-controlled.
     *   <li>Eagerly deletes the extraction directory on any failure, in addition to the JVM
     *       shutdown hook registered as a fallback.
     * </ul>
     *
     * <p>These bounds apply to all callers (URL, filesystem, classpath, package sources).
     *
     * @throws IOException if any zip entry resolves outside the extraction directory, if any size
     *     cap is exceeded, or on I/O errors.
     */
    public static Materialized extractZipSafely(Path zipPath) throws IOException {
        Path extractDir = Files.createTempDirectory(TEMP_DIR_PREFIX);

        // Register the fallback cleanup hook before any work so the empty dir is always reclaimed,
        // even if validation or extraction raises.
        Thread hook = registerCleanup(extractDir);

        try {
            extractZipSafelyInto(zipPath, extractDir);
        } catch (IOException e) {
            // Eager cleanup: the hook is the fallback but callers may never call close() if we
            // throw. Delete now so a failed extraction leaves no partial content behind.
            deleteRecursively(extractDir);
            throw e;
        }

        return new Materialized(extractDir, hook);
    }

    /**
     * Core extraction logic: validates, checks bounds, then extracts. Separated from {@link
     * #extractZipSafely} so the caller can handle cleanup on failure.
     */
    private static void extractZipSafelyInto(Path zipPath, Path extractDir) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zf.entries());

            // Pass 1: zip-slip validation — reject before touching the filesystem.
            for (ZipEntry entry : entries) {
                Path target = extractDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(extractDir)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
            }

            // Pass 2: entry count cap.
            if (entries.size() > MAX_EXTRACT_ENTRIES) {
                throw new IOException(
                        "Skill archive contains "
                                + entries.size()
                                + " entries, exceeding the limit of "
                                + MAX_EXTRACT_ENTRIES);
            }

            // Pass 3: cheap pre-check on declared sizes (attacker-controlled, so treated as a
            // fast early-exit only). Entries reporting -1 (unknown size) are skipped here;
            // the byte counter in Pass 4 is the real enforcement for all entries.
            long totalDeclared = 0;
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }

                long declared = entry.getSize();

                if (declared > MAX_EXTRACT_ENTRY_BYTES) {
                    throw new IOException(
                            "Skill archive entry '"
                                    + entry.getName()
                                    + "' declared size "
                                    + declared
                                    + " exceeds the per-entry limit of "
                                    + MAX_EXTRACT_ENTRY_BYTES
                                    + " bytes");
                }

                if (declared > 0) {
                    totalDeclared += declared;
                }
            }

            if (totalDeclared > MAX_EXTRACT_TOTAL_BYTES) {
                throw new IOException(
                        "Skill archive declared total uncompressed size "
                                + totalDeclared
                                + " exceeds the limit of "
                                + MAX_EXTRACT_TOTAL_BYTES
                                + " bytes");
            }

            // Pass 4: bounded extraction — count actual decompressed bytes written.
            // This is the real enforcement; declared sizes are not trusted.
            long totalWritten = 0;
            byte[] buf = new byte[65536];

            for (ZipEntry entry : entries) {
                Path target = extractDir.resolve(entry.getName()).normalize();

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Files.createDirectories(target.getParent());

                long perEntryWritten = 0;

                // CREATE_NEW preserves the original behavior: duplicate entry names throw
                // FileAlreadyExistsException rather than silently overwriting.
                try (InputStream in = zf.getInputStream(entry);
                        OutputStream out =
                                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {

                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (perEntryWritten + n > MAX_EXTRACT_ENTRY_BYTES) {
                            throw new IOException(
                                    "Skill archive entry '"
                                            + entry.getName()
                                            + "' exceeds the per-entry limit of "
                                            + MAX_EXTRACT_ENTRY_BYTES
                                            + " bytes");
                        }

                        if (totalWritten + n > MAX_EXTRACT_TOTAL_BYTES) {
                            throw new IOException(
                                    "Skill archive total extracted size exceeds the limit of "
                                            + MAX_EXTRACT_TOTAL_BYTES
                                            + " bytes");
                        }

                        out.write(buf, 0, n);
                        perEntryWritten += n;
                        totalWritten += n;
                    }
                }
            }
        }
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
            jarFileObj = LocalUrls.toLocalFile(innerUrl);
        } catch (IOException e) {
            // toLocalFile rejects a non-file inner URL (e.g. a JAR nested behind http://) and
            // malformed URLs. Re-wrap with the outer jar URL for context so callers that catch
            // IOException for graceful failure handling see it.
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
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
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
        return downloadToTempFile(url, timeoutMs, false);
    }

    /**
     * Download {@code url}, optionally permitting plain HTTP transport.
     *
     * <p>Security properties:
     *
     * <ul>
     *   <li>Rejects a declared {@code Content-Length} that exceeds {@link #MAX_DOWNLOAD_BYTES}
     *       before reading any body bytes.
     *   <li>Independently counts bytes as they arrive and rejects the download when the counter
     *       exceeds {@link #MAX_DOWNLOAD_BYTES}, so a missing or understated {@code Content-Length}
     *       cannot bypass the limit.
     *   <li>Deletes the temp file on any failure.
     * </ul>
     *
     * @throws IOException on connect / read failures, HTTP error responses, or size cap exceeded.
     */
    public static Path downloadToTempFile(String url, int timeoutMs, boolean allowInsecureHttp)
            throws IOException {
        URL u;

        try {
            u = new URL(url);
        } catch (MalformedURLException ignored) {
            throw new IOException("Invalid skill URL: " + SkillUrlUtils.redact(url));
        }

        String initialProtocol = requireValidDownloadUrl(u, allowInsecureHttp);
        boolean followRedirects = HttpURLConnection.getFollowRedirects();

        Path tmpZip = Files.createTempFile(TEMP_DIR_PREFIX, ".zip");
        HttpURLConnection conn = null;

        try {
            URL effectiveUrl = u;
            int redirects = 0;

            while (true) {
                conn = (HttpURLConnection) effectiveUrl.openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestMethod("GET");

                // Validate each redirect ourselves before opening its target. This also preserves
                // the JVM-wide switch that lets deployments disable redirects.
                conn.setInstanceFollowRedirects(false);

                int responseCode = conn.getResponseCode();

                if (isRedirectStatus(responseCode)) {
                    String location = conn.getHeaderField("Location");

                    if (location == null) {
                        throw new IOException(
                                "Skill URL returned an invalid redirect to: <redacted>");
                    }

                    URL redirectUrl;

                    try {
                        redirectUrl = new URL(effectiveUrl, location);
                    } catch (MalformedURLException ignored) {
                        throw new IOException(
                                "Skill URL returned an invalid redirect to: "
                                        + SkillUrlUtils.redact(location));
                    }

                    if (!followRedirects) {
                        throw new IOException(
                                "Skill URL returned an unsupported redirect to: "
                                        + SkillUrlUtils.redact(redirectUrl.toExternalForm()));
                    }

                    String redirectProtocol = requireValidDownloadUrl(redirectUrl, true);

                    if (!redirectProtocol.equals(initialProtocol)) {
                        throw new IOException(
                                "Skill URL returned an unsupported redirect to: "
                                        + SkillUrlUtils.redact(redirectUrl.toExternalForm()));
                    }

                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("Skill URL returned too many redirects");
                    }

                    redirects++;
                    conn.disconnect();
                    conn = null;
                    effectiveUrl = redirectUrl;
                    continue;
                }

                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException(
                            "Skill URL returned HTTP "
                                    + responseCode
                                    + ": "
                                    + SkillUrlUtils.redact(effectiveUrl.toExternalForm()));
                }

                // Final 2xx response: bound the download.
                try (InputStream in = conn.getInputStream();
                        OutputStream out =
                                Files.newOutputStream(
                                        tmpZip, StandardOpenOption.TRUNCATE_EXISTING)) {

                    if (!u.toExternalForm().equals(effectiveUrl.toExternalForm())) {
                        LOG.warn(
                                "Skill URL redirected from {} to {}",
                                SkillUrlUtils.redact(u.toExternalForm()),
                                SkillUrlUtils.redact(effectiveUrl.toExternalForm()));
                    }

                    // Pre-flight: reject if Content-Length is declared and already over the cap.
                    // getContentLengthLong() returns -1 when the header is absent or unparseable,
                    // in which case we fall through and let the byte counter catch it.
                    long contentLength = conn.getContentLengthLong();

                    if (contentLength > MAX_DOWNLOAD_BYTES) {
                        throw new IOException(
                                "Skill archive download size declared as "
                                        + contentLength
                                        + " bytes, exceeding the limit of "
                                        + MAX_DOWNLOAD_BYTES
                                        + " bytes");
                    }

                    // Bounded streaming: count actual bytes received so a missing or lying
                    // Content-Length cannot bypass the limit.
                    byte[] buf = new byte[65536];
                    long written = 0;
                    int n;

                    while ((n = in.read(buf)) != -1) {
                        if (written + n > MAX_DOWNLOAD_BYTES) {
                            throw new IOException(
                                    "Skill archive download exceeded the limit of "
                                            + MAX_DOWNLOAD_BYTES
                                            + " bytes");
                        }

                        out.write(buf, 0, n);
                        written += n;
                    }
                }

                break;
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmpZip);
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return tmpZip;
    }

    private static boolean isRedirectStatus(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    private static String requireValidDownloadUrl(URL url, boolean allowInsecureHttp)
            throws IOException {
        try {
            return SkillUrlUtils.validate(url.toExternalForm(), allowInsecureHttp);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
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
