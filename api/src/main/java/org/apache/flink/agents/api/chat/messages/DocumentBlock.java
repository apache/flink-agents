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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import javax.annotation.Nullable;

/** The document content of a {@link ChatMessage} — see {@link MediaBlock} for the media shape. */
public final class DocumentBlock extends MediaBlock {

    @JsonCreator
    public DocumentBlock(
            @JsonProperty("media_type")
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String mediaType,
            @JsonProperty("source") MediaSource source,
            @JsonProperty("name")
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    @Nullable
                    String name,
            @JsonProperty("size_bytes")
                    @JsonDeserialize(using = MediaFieldDeserializers.SizeBytes.class)
                    @Nullable
                    Long sizeBytes,
            @JsonProperty("sha256")
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    @Nullable
                    String sha256) {
        super(mediaType, source, name, sizeBytes, sha256);
    }

    /**
     * Creates a block from non-empty raw bytes, encoded as standard Base64 without line wrapping.
     * The caller's array is not retained; subsequent changes to it do not affect the block.
     *
     * @throws IllegalArgumentException if the media type or data is null or empty
     */
    public static DocumentBlock fromBytes(String mediaType, byte[] data) {
        return new DocumentBlock(mediaType, Base64Source.fromBytes(data), null, null, null);
    }

    /**
     * Creates a block from a non-empty, already Base64-encoded string. The string is preserved
     * without encoding or validating Base64 syntax.
     */
    public static DocumentBlock fromBase64(String mediaType, String data) {
        return new DocumentBlock(mediaType, new Base64Source(data), null, null, null);
    }

    /** Creates a document block referencing an externally managed URL or provider file URI. */
    public static DocumentBlock fromUrl(String mediaType, String url) {
        return new DocumentBlock(mediaType, new UrlSource(url), null, null, null);
    }

    @Override
    public String getType() {
        return "document";
    }
}
