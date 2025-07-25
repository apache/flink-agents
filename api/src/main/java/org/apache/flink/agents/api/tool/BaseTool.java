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

/**
 * Base abstract class for all kinds of tools. Corresponds to the Python BaseTool class.
 *
 * <p>Attributes: ---------- metadata : ToolMetadata The metadata of the tools, includes name,
 * description and arguments schema.
 */
public abstract class BaseTool {

    protected final ToolMetadata metadata;

    protected BaseTool(ToolMetadata metadata) {
        this.metadata = java.util.Objects.requireNonNull(metadata, "metadata cannot be null");
    }

    /** Get the metadata of this tool. */
    public final ToolMetadata getMetadata() {
        return metadata;
    }

    /** Return tool type of class. */
    public abstract ToolType getToolType();

    /**
     * Call the tool with arguments. This is the method that should be implemented by the tool
     * developer.
     */
    public abstract ToolResponse call(ToolParameters parameters);

    /** Convenience method to get the tool name from metadata. */
    public final String getName() {
        return metadata.getName();
    }

    /** Convenience method to get the tool description from metadata. */
    public final String getDescription() {
        return metadata.getDescription();
    }

    /** Convenience method to get the input schema from metadata. */
    public final String getInputSchema() {
        return metadata.getInputSchema();
    }

    /** Execute the tool with validation and error handling. */
    public final ToolResponse execute(ToolParameters parameters) {
        long startTime = System.currentTimeMillis();

        try {
            // Validate parameters against schema
            if (!ToolSchema.validateParameters(parameters, getInputSchema())) {
                return ToolResponse.error(
                        "Invalid parameters for tool '" + getName() + "'",
                        System.currentTimeMillis() - startTime,
                        getName());
            }

            // Execute the tool
            ToolResponse response = call(parameters);

            // Add execution time and tool name if not already set
            if (response.getExecutionTimeMs() == 0) {
                long executionTime = System.currentTimeMillis() - startTime;
                if (response.isSuccess()) {
                    return ToolResponse.success(response.getResult(), executionTime, getName());
                } else {
                    return ToolResponse.error(response.getError(), executionTime, getName());
                }
            }

            return response;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return ToolResponse.error(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    executionTime,
                    getName());
        }
    }
}
