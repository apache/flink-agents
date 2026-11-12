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

package org.apache.flink.agents.api.media;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Output of a finished generation, transcription, or synthesis. */
public final class MediaResult {
    private final List<DataContentBlock> content;
    @Nullable private final Long expiresAtMs;
    private final Map<String, Object> usage;
    @Nullable private final String requestId;
    private final Map<String, Object> metadata;

    @JsonCreator
    public MediaResult(
            @JsonProperty(value = "content", required = true) List<DataContentBlock> content,
            @JsonProperty("expires_at_ms") @Nullable Long expiresAtMs,
            @JsonProperty("usage") @Nullable Map<String, Object> usage,
            @JsonProperty("request_id") @Nullable String requestId,
            @JsonProperty("metadata") @Nullable Map<String, Object> metadata) {
        this.content = content == null ? List.of() : List.copyOf(content);
        this.expiresAtMs = expiresAtMs;
        this.usage = usage == null ? Map.of() : Map.copyOf(usage);
        this.requestId = requestId;
        this.metadata =
                metadata == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public List<DataContentBlock> getContent() {
        return content;
    }

    @JsonProperty("expires_at_ms")
    @Nullable
    public Long getExpiresAtMs() {
        return expiresAtMs;
    }

    public Map<String, Object> getUsage() {
        return usage;
    }

    @JsonProperty("request_id")
    @Nullable
    public String getRequestId() {
        return requestId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
