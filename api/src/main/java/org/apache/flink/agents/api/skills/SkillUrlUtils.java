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

package org.apache.flink.agents.api.skills;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Shared validation and redaction helpers for URL-backed skill sources. */
public final class SkillUrlUtils {

    private SkillUrlUtils() {}

    /**
     * Validate {@code url} and return its lowercase {@code http} or {@code https} scheme.
     *
     * @throws IllegalArgumentException if the URL is invalid or violates the transport policy.
     */
    public static String validate(String url, boolean allowInsecureHttp) {
        if (url == null) {
            throw new IllegalArgumentException("skill URL must not be null");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Invalid skill URL: " + redact(url));
        }
        String scheme = uri.getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException(
                    "Only HTTP(S) skill URLs are supported: " + redact(url));
        }
        try {
            uri = uri.parseServerAuthority();
        } catch (URISyntaxException ignored) {
            throw new IllegalArgumentException(
                    "Skill URL must include a valid host and, when present, a valid port: "
                            + redact(url));
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Skill URL must not include user info: " + redact(url));
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException(
                    "Skill URL must include a valid host: " + redact(url));
        }
        if (uri.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "Skill URL port must be between 0 and 65535: " + redact(url));
        }
        if (scheme.equals("http") && !allowInsecureHttp) {
            throw new IllegalArgumentException(
                    "Plain HTTP skill URLs are disabled by default; use HTTPS or explicitly allow"
                            + " insecure HTTP for this source: "
                            + redact(url));
        }
        return scheme;
    }

    /** Return {@code url} without user info, query parameters, or a fragment. */
    public static String redact(String url) {
        if (url == null) {
            return "<redacted>";
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return "<redacted>";
            }
            String authority = uri.getRawAuthority();
            int userInfoEnd = authority.lastIndexOf('@');
            if (userInfoEnd >= 0) {
                authority = authority.substring(userInfoEnd + 1);
            }
            if (authority.isEmpty()) {
                return "<redacted>";
            }
            return uri.getScheme() + "://" + authority + uri.getRawPath();
        } catch (IllegalArgumentException ignored) {
            return "<redacted>";
        }
    }
}
