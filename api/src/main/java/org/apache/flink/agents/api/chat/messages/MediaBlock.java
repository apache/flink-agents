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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared shape for binary media blocks: modality is the concrete type, encoding is the media type
 * (RFC 6838; historically called a MIME type).
 *
 * <p>Media blocks are immutable, and every construction path — the {@code fromBase64}/{@code
 * fromUrl} factories, the full constructors, and Jackson deserialization — runs the same
 * validation, so a block that exists carries exactly one of base64 {@code data} or an externally
 * managed {@code url}. URL-backed content is externally managed: URLs may expire, may not be
 * reachable by the model provider, and may be invalid after recovery from a checkpoint.
 *
 * <p>The optional {@code name}/{@code sizeBytes}/{@code sha256} metadata also serves the Event Log,
 * which records media metadata instead of payload bytes — see {@link #sanitize()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class MediaBlock extends ContentBlock {

    @JsonProperty("media_type")
    private final String mediaType;

    @Nullable private final String data;

    @Nullable private final String url;

    @Nullable private final String name;

    @JsonProperty("size_bytes")
    @Nullable
    private final Long sizeBytes;

    @Nullable private final String sha256;

    protected MediaBlock(
            String mediaType,
            @Nullable String data,
            @Nullable String url,
            @Nullable String name,
            @Nullable Long sizeBytes,
            @Nullable String sha256) {
        if (mediaType == null || mediaType.isEmpty()) {
            throw new IllegalArgumentException("A media block requires a media type.");
        }
        if ((data == null) == (url == null)) {
            throw new IllegalArgumentException(
                    "A media block carries exactly one of base64 data or a URL.");
        }
        this.mediaType = mediaType;
        this.data = data;
        this.url = url;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }

    @JsonProperty("media_type")
    public String getMediaType() {
        return mediaType;
    }

    @Nullable
    public String getData() {
        return data;
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    @Nullable
    public String getName() {
        return name;
    }

    @JsonProperty("size_bytes")
    @Nullable
    public Long getSizeBytes() {
        return sizeBytes;
    }

    @Nullable
    public String getSha256() {
        return sha256;
    }

    /**
     * The metadata-only Event Log projection: type, media type, and the optional {@code
     * name}/{@code size_bytes}/{@code sha256}. Inline {@code data} is dropped entirely (with {@code
     * size_bytes} derived from the base64 length when not stored), and URLs are stripped of
     * credentials, query, and fragment. Dropping {@code data} rather than masking it means an
     * attempt to reconstruct a block from logged output fails loudly instead of yielding a block
     * with a fake payload.
     */
    @Override
    public final Map<String, Object> sanitize() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", getType());
        safe.put("media_type", mediaType);
        if (name != null) {
            safe.put("name", name);
        }
        Long size = sizeBytes != null ? sizeBytes : decodedSizeOf(data);
        if (size != null) {
            safe.put("size_bytes", size);
        }
        if (sha256 != null) {
            safe.put("sha256", sha256);
        }
        if (url != null) {
            safe.put("url", sanitizeUrl(url));
        }
        return safe;
    }

    /** The decoded byte count implied by a base64 payload, or null when there is none. */
    @Nullable
    private static Long decodedSizeOf(@Nullable String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        long padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0;
        return data.length() * 3L / 4 - padding;
    }

    /**
     * Reduces a URL to scheme, host, port, and path. Userinfo (credentials), query strings (signed
     * URLs carry their tokens there), and fragments never reach the log; a URL that cannot be
     * parsed is replaced entirely rather than logged raw.
     */
    private static String sanitizeUrl(String url) {
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
        if (o == null || getClass() != o.getClass()) return false;
        MediaBlock that = (MediaBlock) o;
        return Objects.equals(mediaType, that.mediaType)
                && Objects.equals(data, that.data)
                && Objects.equals(url, that.url)
                && Objects.equals(name, that.name)
                && Objects.equals(sizeBytes, that.sizeBytes)
                && Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mediaType, data, url, name, sizeBytes, sha256);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "("
                + mediaType
                + ", "
                + (data != null ? "inline" : "url=" + url)
                + ")";
    }
}
