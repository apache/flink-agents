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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An inline media payload, carried as base64 text. */
public final class Base64Source extends MediaSource {

    private final String data;

    @JsonCreator
    public Base64Source(@JsonProperty("data") String data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("A base64 source requires a non-empty payload.");
        }
        this.data = data;
    }

    public String getData() {
        return data;
    }

    @Override
    public String getType() {
        return "base64";
    }

    /** The decoded byte count implied by the base64 length. */
    @Override
    public Long getSizeBytes() {
        long padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0;
        return data.length() * 3L / 4 - padding;
    }

    /**
     * Only the discriminator: the payload is dropped entirely rather than masked, so an attempt to
     * reconstruct a source from logged output fails loudly instead of yielding a fake payload.
     */
    @Override
    public Map<String, Object> sanitize() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", getType());
        return safe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Base64Source)) return false;
        return Objects.equals(data, ((Base64Source) o).data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data);
    }

    @Override
    public String toString() {
        return "Base64Source(" + getSizeBytes() + " bytes)";
    }
}
