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

/** Enumeration of tool types supported by Flink-Agents. */
public enum ToolType {
    /** Tool provided by the model provider (e.g., "web_search_preview" of OpenAI models). */
    MODEL_BUILT_IN("model_built_in"),

    /** Python/Java function defined by user. */
    FUNCTION("function"),

    /** Remote function indicated by name. */
    REMOTE_FUNCTION("remote_function"),

    /** Tool provided by MCP server. */
    MCP("mcp");

    private final String value;

    ToolType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static ToolType fromString(String value) {
        for (ToolType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown tool type: " + value);
    }
}
