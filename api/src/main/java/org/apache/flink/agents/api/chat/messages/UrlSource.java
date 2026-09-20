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

package org.apache.flink.agents.api.chat.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An externally managed media location: a URL or a provider file URI.
 *
 * <p>The location is externally managed: it may expire, may not be reachable by the model provider,
 * and may be invalid after recovery from a checkpoint.
 */
public final class UrlSource extends MediaSource {

    private final String url;

    @JsonCreator
    public UrlSource(@JsonProperty("url") String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("A URL source requires a non-empty URL.");
        }
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String getType() {
        return "url";
    }

    /** Unknown without fetching the location. */
    @Override
    public Long getSizeBytes() {
        return null;
    }

    /**
     * The URL reduced to scheme, host, port, and path. Userinfo (credentials), query strings
     * (signed URLs carry their tokens there), and fragments never reach the log; a URL that cannot
     * be parsed is replaced entirely rather than logged raw.
     */
    @Override
    public Map<String, Object> sanitize() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", getType());
        safe.put("url", strip(url));
        return safe;
    }

    private static String strip(String url) {
        try {
            URI uri = new URI(url);
            if (uri.isOpaque()) {
                return uri.getScheme() + ":<redacted>";
            }
            return new URI(
                            uri.getScheme(),
                            null,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            null,
                            null)
                    .toString();
        } catch (URISyntaxException e) {
            return "<unparseable-url>";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UrlSource)) return false;
        return Objects.equals(url, ((UrlSource) o).url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    @Override
    public String toString() {
        return "UrlSource(" + url + ")";
    }
}
