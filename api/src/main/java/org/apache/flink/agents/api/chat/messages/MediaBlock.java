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

import java.util.Objects;

/**
 * Shared shape for binary media blocks: modality is the concrete type, encoding is the MIME type.
 *
 * <p>The payload is carried by exactly one of base64 {@code data} or an externally managed {@code
 * url} (enforced by the argument constructor and the per-type factories; the no-arg bean path is
 * lenient for deserialization). URL-backed content is externally managed: URLs may expire, may not
 * be reachable by the model provider, and may be invalid after recovery from a checkpoint. The
 * optional {@code name}/{@code sizeBytes}/{@code sha256} metadata also serves the Event Log, which
 * records media metadata instead of payload bytes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class MediaBlock extends ContentBlock {

    @JsonProperty("mime_type")
    private String mimeType;

    @Nullable private String data;

    @Nullable private String url;

    @Nullable private String name;

    @JsonProperty("size_bytes")
    @Nullable
    private Long sizeBytes;

    @Nullable private String sha256;

    protected MediaBlock() {}

    protected MediaBlock(String mimeType, @Nullable String data, @Nullable String url) {
        if (mimeType == null || mimeType.isEmpty()) {
            throw new IllegalArgumentException("A media block requires a MIME type.");
        }
        if ((data == null) == (url == null)) {
            throw new IllegalArgumentException(
                    "A media block carries exactly one of base64 data or a URL.");
        }
        this.mimeType = mimeType;
        this.data = data;
        this.url = url;
    }

    @JsonProperty("mime_type")
    public String getMimeType() {
        return mimeType;
    }

    @JsonProperty("mime_type")
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    @Nullable
    public String getData() {
        return data;
    }

    public void setData(@Nullable String data) {
        this.data = data;
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    public void setUrl(@Nullable String url) {
        this.url = url;
    }

    @Nullable
    public String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }

    @JsonProperty("size_bytes")
    @Nullable
    public Long getSizeBytes() {
        return sizeBytes;
    }

    @JsonProperty("size_bytes")
    public void setSizeBytes(@Nullable Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    @Nullable
    public String getSha256() {
        return sha256;
    }

    public void setSha256(@Nullable String sha256) {
        this.sha256 = sha256;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaBlock that = (MediaBlock) o;
        return Objects.equals(mimeType, that.mimeType)
                && Objects.equals(data, that.data)
                && Objects.equals(url, that.url)
                && Objects.equals(name, that.name)
                && Objects.equals(sizeBytes, that.sizeBytes)
                && Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mimeType, data, url, name, sizeBytes, sha256);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "("
                + mimeType
                + ", "
                + (data != null ? "inline" : "url=" + url)
                + ")";
    }
}
