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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Provider reasoning; never included in the ordinary text projection. */
public final class ReasoningBlock extends ContentBlock {
    private static final String TYPE_FIELD = "type";
    private static final String TEXT_FIELD = "text";
    private static final String METADATA_FIELD = "metadata";

    private final String text;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ReasoningBlock(
            @JsonProperty(TEXT_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String text,
            @JsonProperty(METADATA_FIELD) Map<String, Object> metadata) {
        this.text = text;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }

    public ReasoningBlock(String text) {
        this(text, null);
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String getType() {
        return "reasoning";
    }

    @Override
    public Map<String, Object> sanitize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TYPE_FIELD, getType());
        result.put(TEXT_FIELD, text);
        result.put(METADATA_FIELD, metadata);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReasoningBlock
                && Objects.equals(text, ((ReasoningBlock) o).text)
                && metadata.equals(((ReasoningBlock) o).metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, metadata);
    }
}
