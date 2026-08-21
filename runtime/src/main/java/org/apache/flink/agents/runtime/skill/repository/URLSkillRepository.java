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

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Skill repository backed by an HTTPS URL pointing to a zip.
 *
 * <p>The zip is downloaded to a temp file and extracted into a process-local temp directory. The
 * downloaded zip itself is removed once extraction completes; the extracted directory is released
 * via {@link #close()} (cascaded through {@code SkillManager} → {@code ResourceContextImpl} →
 * {@code ResourceCache} on operator close). A JVM shutdown hook acts as fallback cleanup if {@code
 * close()} is never called. Plain HTTP is rejected unless the caller explicitly opts in, and an
 * optional SHA-256 digest is verified before extraction.
 */
public final class URLSkillRepository extends AbstractMaterializedSkillRepository {

    private static final int REQUEST_TIMEOUT_MS = 90_000;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private final String url;

    public URLSkillRepository(String url) throws IOException {
        this(url, null, false);
    }

    public URLSkillRepository(String url, @Nullable String sha256, boolean allowInsecureHttp)
            throws IOException {
        super(materialize(url, sha256, allowInsecureHttp));
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    private static SkillMaterializer.Materialized materialize(
            String url, @Nullable String sha256, boolean allowInsecureHttp) throws IOException {
        URI uri;
        if (url == null) {
            throw new IllegalArgumentException("skill URL must not be null");
        }
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid skill URL: " + url, e);
        }
        String scheme = uri.getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("Only HTTP(S) URLs are supported: " + url);
        }
        if (scheme.equals("http") && !allowInsecureHttp) {
            throw new IllegalArgumentException(
                    "Plain HTTP skill URLs are disabled by default; use HTTPS or explicitly allow"
                            + " insecure HTTP for this source: "
                            + url);
        }
        if (uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty()) {
            throw new IllegalArgumentException("Skill URL must include a host: " + url);
        }
        String normalizedSha256 = sha256 == null ? null : sha256.toLowerCase(Locale.ROOT);
        if (normalizedSha256 != null && !SHA256_PATTERN.matcher(normalizedSha256).matches()) {
            throw new IllegalArgumentException(
                    "sha256 must contain exactly 64 hexadecimal characters");
        }
        Path tmpZip =
                SkillMaterializer.downloadToTempFile(url, REQUEST_TIMEOUT_MS, allowInsecureHttp);
        try {
            if (normalizedSha256 != null) {
                String actual = sha256(tmpZip);
                if (!actual.equals(normalizedSha256)) {
                    throw new IllegalArgumentException(
                            "SHA-256 mismatch for skill archive "
                                    + url
                                    + ": expected "
                                    + normalizedSha256
                                    + ", got "
                                    + actual);
                }
            }
            return SkillMaterializer.extractZipSafely(tmpZip);
        } finally {
            Files.deleteIfExists(tmpZip);
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return hex.toString();
    }
}
