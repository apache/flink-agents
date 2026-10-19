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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** A reference to a child Agent in this file or already registered in the environment. */
public final class InternalSubagentSpec {
    private final String name;
    private final String agent;
    private final String description;
    private final Map<String, Object> inputSchema;

    @JsonCreator
    public InternalSubagentSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "agent", required = true) String agent,
            @JsonProperty("description") String description,
            @JsonProperty("input_schema") Map<String, Object> inputSchema) {
        if (agent == null || agent.isBlank()) {
            throw new IllegalArgumentException(
                    "Internal sub-agent 'agent' must be a non-empty name");
        }
        this.name = name;
        this.agent = agent;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getAgent() {
        return agent;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }
}
