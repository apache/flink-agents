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

package org.apache.flink.agents.api.tool;

import java.util.Objects;

/** Represents the definition of a tool, containing its name, description, and input schema. */
public class ToolDefinition {

    private final String name;
    private final String description;
    private final String inputSchema;

    /**
     * Creates a new ToolDefinition.
     *
     * @param name the name of the tool
     * @param description the description of what the tool does
     * @param inputSchema the JSON schema defining the tool's input parameters
     */
    public ToolDefinition(String name, String description, String inputSchema) {
        this.name = Objects.requireNonNull(name, "Tool name cannot be null");
        this.description = description != null ? description : "";
        this.inputSchema = inputSchema != null ? inputSchema : "{}";
    }

    /**
     * Gets the tool name.
     *
     * @return the tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the tool description.
     *
     * @return the tool description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets the input schema.
     *
     * @return the JSON schema defining input parameters
     */
    public String getInputSchema() {
        return inputSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolDefinition that = (ToolDefinition) o;
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
        return "ToolDefinition{"
                + "name='"
                + name
                + '\''
                + ", description='"
                + description
                + '\''
                + ", inputSchema='"
                + inputSchema
                + '\''
                + '}';
    }
}
