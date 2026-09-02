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

import org.apache.flink.annotation.Internal;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared validation and redaction helpers for URL-backed skill sources. Internal contract shared
 * with the runtime module; not a stable public API.
 */
@Internal
public final class SkillUrlUtils {

    private static final String REDACTED = "<redacted>";
    private static final Pattern INVALID_PERCENT_ESCAPE = Pattern.compile("%(?![0-9a-fA-F]{2})");

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
        if (INVALID_PERCENT_ESCAPE.matcher(url).find()) {
            throw new IllegalArgumentException("Invalid skill URL: " + redact(url));
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
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException(
                    "Skill URL must include a valid host: " + redact(url));
        }
        if (host.indexOf(':') >= 0 && host.indexOf('%') >= 0) {
            throw new IllegalArgumentException(
                    "Skill URL must not include an IPv6 zone identifier: " + redact(url));
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
            return REDACTED;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return REDACTED;
            }
            return redactParts(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath());
        } catch (IllegalArgumentException ignored) {
            try {
                URL parsed = new URL(url);
                String authority = parsed.getAuthority();
                String path = parsed.getPath();
                if (authority == null
                        || containsUnsafeLogCharacter(authority)
                        || containsUnsafeLogCharacter(path)) {
                    return REDACTED;
                }
                return redactParts(parsed.getProtocol(), authority, path);
            } catch (MalformedURLException | IllegalArgumentException malformed) {
                return REDACTED;
            }
        }
    }

    private static String redactParts(String scheme, String authority, String path) {
        int userInfoEnd = authority.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            authority = authority.substring(userInfoEnd + 1);
        }
        if (authority.isEmpty() || hasInvalidPort(authority)) {
            return REDACTED;
        }
        return scheme + "://" + authority + (path == null ? "" : path);
    }

    private static boolean hasInvalidPort(String authority) {
        int portStart;
        if (authority.startsWith("[")) {
            int closingBracket = authority.lastIndexOf(']');
            if (closingBracket < 0) {
                return true;
            }
            if (closingBracket == authority.length() - 1) {
                return false;
            }
            if (authority.charAt(closingBracket + 1) != ':') {
                return true;
            }
            portStart = closingBracket + 2;
        } else {
            int firstColon = authority.indexOf(':');
            if (firstColon < 0) {
                return false;
            }
            if (firstColon != authority.lastIndexOf(':')) {
                return true;
            }
            portStart = firstColon + 1;
        }
        if (portStart == authority.length()) {
            return false;
        }
        int port = 0;
        for (int i = portStart; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c < '0' || c > '9') {
                return true;
            }
            port = port * 10 + (c - '0');
            if (port > 65535) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsafeLogCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
