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
package org.apache.flink.agents.plan.resource.python;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;

import java.util.Map;

/**
 * PythonTool is a subclass of Tool that that provides a method to parse a Python tool metadata from
 * a serialized map.
 */
public class PythonTool extends Tool {
    protected PythonTool(ToolMetadata metadata) {
        super(metadata);
    }

    @SuppressWarnings("unchecked")
    public static PythonTool fromSerializedMap(Map<String, Object> serialized)
            throws JsonProcessingException {
        if (serialized == null) {
            throw new IllegalArgumentException("Serialized map cannot be null");
        }

        if (!serialized.containsKey("metadata")) {
            throw new IllegalArgumentException("Map must contain 'metadata' key");
        }

        Object metadataObj = serialized.get("metadata");
        if (!(metadataObj instanceof Map)) {
            throw new IllegalArgumentException("'metadata' must be a Map");
        }

        Map<String, Object> metadata = (Map<String, Object>) metadataObj;

        if (!metadata.containsKey("name")) {
            throw new IllegalArgumentException("Metadata must contain 'name' key");
        }

        if (!metadata.containsKey("description")) {
            throw new IllegalArgumentException("Metadata must contain 'description' key");
        }

        if (!metadata.containsKey("args_schema")) {
            throw new IllegalArgumentException("Metadata must contain 'args_schema' key");
        }

        String name = (String) metadata.get("name");
        String description = (String) metadata.get("description");

        if (name == null) {
            throw new IllegalArgumentException("'name' cannot be null");
        }

        if (description == null) {
            throw new IllegalArgumentException("'description' cannot be null");
        }

        ObjectMapper mapper = new ObjectMapper();
        String inputSchema = mapper.writeValueAsString(metadata.get("args_schema"));
        return new PythonTool(new ToolMetadata(name, description, inputSchema));
    }

    @Override
    public ToolType getToolType() {
        return ToolType.REMOTE_FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        throw new UnsupportedOperationException("PythonTool does not support call method.");
    }
}
