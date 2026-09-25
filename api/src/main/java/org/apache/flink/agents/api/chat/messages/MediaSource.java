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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import javax.annotation.Nullable;

import java.util.Map;

/**
 * Where a {@link MediaBlock}'s payload lives.
 *
 * <p>The source is a discriminated, immutable value: a block carries exactly one source, and the
 * kind of source is structural rather than a validation rule over nullable fields. The initial
 * sources are {@link Base64Source} (inline payload) and {@link UrlSource} (externally managed
 * location); a managed blob/reference source can be added later without touching the block shape.
 * Providers explicitly convert or reject the source kinds they support.
 *
 * <p>The serialized form carries a {@code type} discriminator ({@code base64}, {@code url}) shared
 * with the Python API.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Base64Source.class, name = "base64"),
    @JsonSubTypes.Type(value = UrlSource.class, name = "url")
})
public abstract class MediaSource {

    /** The wire discriminator of this source: {@code base64} or {@code url}. */
    @JsonIgnore
    public abstract String getType();

    /**
     * The payload size in bytes when the source can tell without fetching anything, otherwise null.
     */
    @JsonIgnore
    @Nullable
    public abstract Long getSizeBytes();

    /**
     * The log-safe projection of this source, as a plain map in the wire's snake_case shape: never
     * payload bytes, never credentials — see {@link ContentBlock#sanitize()}.
     */
    public abstract Map<String, Object> sanitize();
}
