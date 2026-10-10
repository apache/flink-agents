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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents the content and status of one tool execution.
 *
 * <p>The ordered {@link #getBlocks() blocks} contain the text and media intended for the model.
 * Optional {@link #getMetadata() metadata} holds application data and is not copied into the
 * model's tool-result message. A tool does not have to duplicate its content in metadata.
 *
 * <p>Use {@link #text(String)} for text, {@link #success(List)} for explicit content blocks, and
 * {@link #error(String)} for an unsuccessful operation. Function-tool adapters convert ordinary
 * return values into text blocks before constructing a response. Media content still requires
 * support from the selected model and provider adapter.
 */
public class ToolResponse {

    private final List<DataContentBlock> blocks;
    private final Map<String, Object> metadata;
    private final boolean success;
    private final String error;

    @JsonProperty("execution_time_ms")
    private final long executionTimeMs;

    @JsonProperty("tool_name")
    private final String toolName;

    @JsonCreator
    private ToolResponse(
            @JsonProperty("blocks") List<? extends DataContentBlock> blocks,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("success") boolean success,
            @JsonProperty("error") String error,
            @JsonProperty("execution_time_ms") long executionTimeMs,
            @JsonProperty("tool_name") String toolName) {
        if (success != (error == null)) {
            throw new IllegalArgumentException("ToolResponse success and error disagree");
        }
        // Kryo restores collections by adding elements to the backing list.
        this.blocks = blocks == null ? new ArrayList<>() : new ArrayList<>(List.copyOf(blocks));
        this.metadata = metadata == null ? Map.of() : metadata;
        this.success = success;
        this.error = error;
        this.executionTimeMs = executionTimeMs;
        this.toolName = toolName;
    }

    /** Create a successful response with ordered text and media blocks. */
    public static ToolResponse success(List<? extends DataContentBlock> blocks) {
        return success(blocks, 0, null);
    }

    /** Create a successful response with execution time and tool name. */
    public static ToolResponse success(
            List<? extends DataContentBlock> blocks, long executionTimeMs, String toolName) {
        return new ToolResponse(blocks, null, true, null, executionTimeMs, toolName);
    }

    /** Create a successful response containing one text block. */
    public static ToolResponse text(String text) {
        return text(text, 0, null);
    }

    /** Create a text response with execution time and tool name. */
    public static ToolResponse text(String text, long executionTimeMs, String toolName) {
        return success(List.of(new TextBlock(text)), executionTimeMs, toolName);
    }

    /** Create an error response. */
    public static ToolResponse error(String error) {
        return error(error, 0, null);
    }

    /** Create an error response with execution time. */
    public static ToolResponse error(String error, long executionTimeMs) {
        return error(error, executionTimeMs, null);
    }

    /** Create an error response with execution time and tool name. */
    public static ToolResponse error(String error, long executionTimeMs, String toolName) {
        return new ToolResponse(
                List.of(),
                null,
                false,
                Objects.requireNonNull(error, "error cannot be null"),
                executionTimeMs,
                toolName);
    }

    /** Create an error response from an exception. */
    public static ToolResponse error(Throwable throwable) {
        return error(throwable, 0);
    }

    /** Create an error response from an exception with execution time. */
    public static ToolResponse error(Throwable throwable, long executionTimeMs) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            message = throwable.getClass().getSimpleName();
        }
        return error(message, executionTimeMs);
    }

    public List<DataContentBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    /** Return application data, which is not automatically sent to the model. */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public ToolResponse withBlocks(List<? extends DataContentBlock> blocks) {
        return new ToolResponse(blocks, metadata, success, error, executionTimeMs, toolName);
    }

    public ToolResponse withMetadata(Map<String, Object> metadata) {
        return new ToolResponse(blocks, metadata, success, error, executionTimeMs, toolName);
    }

    /** Associate model-facing content with a tool call without copying execution metadata. */
    public ToolResultBlock toResultBlock(String callId) {
        if (!success) {
            return new ToolResultBlock(callId, List.of(new TextBlock(error)), true);
        }
        return new ToolResultBlock(callId, blocks, false);
    }

    /** Concatenate text blocks in order; media and metadata are excluded. */
    @JsonIgnore
    public String getText() {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    public boolean isSuccess() {
        return success;
    }

    @JsonIgnore
    public boolean isError() {
        return !success;
    }

    public String getError() {
        return error;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public String getToolName() {
        return toolName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolResponse that = (ToolResponse) o;
        return success == that.success
                && executionTimeMs == that.executionTimeMs
                && Objects.equals(blocks, that.blocks)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(error, that.error)
                && Objects.equals(toolName, that.toolName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blocks, metadata, success, error, executionTimeMs, toolName);
    }

    @Override
    public String toString() {
        return success ? getText() : error;
    }
}
