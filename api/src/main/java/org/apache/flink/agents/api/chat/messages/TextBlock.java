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
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A plain-text, immutable part of a {@link ChatMessage}. */
public final class TextBlock extends ContentBlock {

    private final String text;

    public TextBlock(String text) {
        if (text == null) {
            throw new IllegalArgumentException("A text block requires non-null text.");
        }
        this.text = text;
    }

    /**
     * Wire-format construction, matching the Python model: an omitted {@code text} defaults to the
     * empty string, while an explicit {@code null} or a non-string value is rejected. The parameter
     * is a {@link JsonNode} because a {@code String} parameter cannot tell those two cases apart —
     * Jackson passes {@code null} for both.
     */
    @JsonCreator
    static TextBlock fromJson(@JsonProperty("text") JsonNode text) {
        if (text == null) {
            return new TextBlock("");
        }
        if (!text.isTextual()) {
            throw new IllegalArgumentException(
                    "A text block's text must be a string, got " + text.getNodeType() + ".");
        }
        return new TextBlock(text.textValue());
    }

    public static TextBlock of(String text) {
        return new TextBlock(text);
    }

    public String getText() {
        return text;
    }

    @Override
    public String getType() {
        return "text";
    }

    @Override
    public Map<String, Object> sanitize() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", getType());
        safe.put("text", text);
        return safe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextBlock)) return false;
        return Objects.equals(text, ((TextBlock) o).text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
