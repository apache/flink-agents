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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared shape for binary media blocks: modality is the concrete type, encoding is the media type
 * (RFC 6838; historically called a MIME type), and the payload location is a typed {@link
 * MediaSource}.
 *
 * <p>Media blocks are immutable, and every construction path — the {@code fromBase64}/{@code
 * fromUrl} factories, the full constructors, and Jackson deserialization — runs the same
 * validation. Which kind of source a block carries is structural: there is exactly one {@code
 * source}, and its {@code type} discriminator says whether it is an inline {@link Base64Source} or
 * an externally managed {@link UrlSource}.
 *
 * <p>The optional {@code name}/{@code sizeBytes}/{@code sha256} metadata also serves the Event Log,
 * which records media metadata instead of payload bytes — see {@link #sanitize()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class MediaBlock extends ContentBlock {

    @JsonProperty("media_type")
    private final String mediaType;

    private final MediaSource source;

    @Nullable private final String name;

    @JsonProperty("size_bytes")
    @Nullable
    private final Long sizeBytes;

    @Nullable private final String sha256;

    protected MediaBlock(
            String mediaType,
            MediaSource source,
            @Nullable String name,
            @Nullable Long sizeBytes,
            @Nullable String sha256) {
        if (mediaType == null || mediaType.isEmpty()) {
            throw new IllegalArgumentException("A media block requires a media type.");
        }
        if (source == null) {
            throw new IllegalArgumentException("A media block requires a source.");
        }
        this.mediaType = mediaType;
        this.source = source;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }

    @JsonProperty("media_type")
    public String getMediaType() {
        return mediaType;
    }

    public MediaSource getSource() {
        return source;
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
     * The metadata-only Event Log projection: type, media type, the optional {@code name}/{@code
     * size_bytes}/{@code sha256} (with {@code size_bytes} derived from the source when not stored),
     * and the source's own log-safe projection — never payload bytes, never credentials.
     */
    @Override
    public final Map<String, Object> sanitize() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", getType());
        safe.put("media_type", mediaType);
        if (name != null) {
            safe.put("name", name);
        }
        Long size = sizeBytes != null ? sizeBytes : source.getSizeBytes();
        if (size != null) {
            safe.put("size_bytes", size);
        }
        if (sha256 != null) {
            safe.put("sha256", sha256);
        }
        safe.put("source", source.sanitize());
        return safe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaBlock that = (MediaBlock) o;
        return Objects.equals(mediaType, that.mediaType)
                && Objects.equals(source, that.source)
                && Objects.equals(name, that.name)
                && Objects.equals(sizeBytes, that.sizeBytes)
                && Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mediaType, source, name, sizeBytes, sha256);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + mediaType + ", " + source + ")";
    }
}
