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

package org.apache.flink.agents.api.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * API-level metadata for a tool. This stays in the API module as it's a core data model that users
 * interact with. Implementation logic for creating metadata from methods is handled in the plan
 * module.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolMetadata {
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_INPUT_SCHEMA = "inputSchema";

    @JsonProperty(FIELD_NAME)
    private final String name;

    @JsonProperty(FIELD_DESCRIPTION)
    private final String description;

    @JsonProperty(FIELD_INPUT_SCHEMA)
    private final String inputSchema;

    @JsonCreator
    public ToolMetadata(
            @JsonProperty(FIELD_NAME) String name,
            @JsonProperty(FIELD_DESCRIPTION) String description,
            @JsonProperty(FIELD_INPUT_SCHEMA) String inputSchema) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema cannot be null");
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolMetadata that = (ToolMetadata) o;
        return Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(inputSchema, that.inputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, inputSchema);
    }

    @Override
    public String toString() {
        return String.format("ToolMetadata{name='%s', description='%s'}", name, description);
    }
}
