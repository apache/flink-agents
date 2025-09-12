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

import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

/**
 * Simplified base class for all tools. Focus on essential tool functionality without
 * overcomplicated features.
 */
public abstract class BaseTool extends SerializableResource {

    protected final ToolMetadata metadata;

    protected BaseTool(ToolMetadata metadata) {
        this.metadata = java.util.Objects.requireNonNull(metadata, "metadata cannot be null");
    }

    /** Get the metadata of this tool. */
    public final ToolMetadata getMetadata() {
        return metadata;
    }

    /** Return resource type of class. */
    @Override
    public final ResourceType getResourceType() {
        return ResourceType.TOOL;
    }

    /** Return tool type of class. */
    public abstract ToolType getToolType();

    /** Call the tool with parameters. This is the core method for tool execution. */
    public abstract ToolResponse call(ToolParameters parameters);

    /** Get the tool name from metadata. */
    public final String getName() {
        return metadata.getName();
    }

    /** Get the tool description from metadata. */
    public final String getDescription() {
        return metadata.getDescription();
    }
}
