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

package org.apache.flink.agents.api.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/** Callable description and object input schema for an agent registered as a sub-agent. */
public final class SubagentMetadata {
    private final String description;
    private final String inputSchema;

    @JsonCreator
    public SubagentMetadata(
            @JsonProperty("description") String description,
            @JsonProperty("input_schema") String inputSchema) {
        this.description =
                Objects.requireNonNull(description, "Sub-agent description is required.");
        this.inputSchema =
                Objects.requireNonNull(inputSchema, "Sub-agent input schema is required.");
        try {
            JsonNode schema =
                    new ObjectMapper()
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(inputSchema);
            if (schema == null
                    || !schema.isObject()
                    || !"object".equals(schema.path("type").asText())) {
                throw new IllegalArgumentException(
                        "Sub-agent input schema must describe a JSON object.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Sub-agent input schema must be valid JSON.", e);
        }
    }

    @JsonProperty("description")
    public String getDescription() {
        return description;
    }

    @JsonProperty("input_schema")
    public String getInputSchema() {
        return inputSchema;
    }
}
