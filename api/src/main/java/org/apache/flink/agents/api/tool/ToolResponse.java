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

/**
 * Represents the response from a tool execution. Contains the result data, success status, and any
 * error information.
 */
public class ToolResponse {

    private final Object result;
    private final boolean success;
    private final String error;
    private final long executionTimeMs;
    private final String toolName;

    private ToolResponse(
            Object result, boolean success, String error, long executionTimeMs, String toolName) {
        this.result = result;
        this.success = success;
        this.error = error;
        this.executionTimeMs = executionTimeMs;
        this.toolName = toolName;
    }

    /** Create a successful response with result. */
    public static ToolResponse success(Object result) {
        return new ToolResponse(result, true, null, 0, null);
    }

    /** Create a successful response with result and execution time. */
    public static ToolResponse success(Object result, long executionTimeMs) {
        return new ToolResponse(result, true, null, executionTimeMs, null);
    }

    /** Create a successful response with result, execution time, and tool name. */
    public static ToolResponse success(Object result, long executionTimeMs, String toolName) {
        return new ToolResponse(result, true, null, executionTimeMs, toolName);
    }

    /** Create an error response. */
    public static ToolResponse error(String error) {
        return new ToolResponse(
                null, false, Objects.requireNonNull(error, "error cannot be null"), 0, null);
    }

    /** Create an error response with execution time. */
    public static ToolResponse error(String error, long executionTimeMs) {
        return new ToolResponse(
                null,
                false,
                Objects.requireNonNull(error, "error cannot be null"),
                executionTimeMs,
                null);
    }

    /** Create an error response with execution time and tool name. */
    public static ToolResponse error(String error, long executionTimeMs, String toolName) {
        return new ToolResponse(
                null,
                false,
                Objects.requireNonNull(error, "error cannot be null"),
                executionTimeMs,
                toolName);
    }

    /** Create an error response from an exception. */
    public static ToolResponse error(Throwable throwable) {
        String errorMessage = throwable.getMessage();
        if (errorMessage == null || errorMessage.isEmpty()) {
            errorMessage = throwable.getClass().getSimpleName();
        }
        return new ToolResponse(null, false, errorMessage, 0, null);
    }

    /** Create an error response from an exception with execution time. */
    public static ToolResponse error(Throwable throwable, long executionTimeMs) {
        String errorMessage = throwable.getMessage();
        if (errorMessage == null || errorMessage.isEmpty()) {
            errorMessage = throwable.getClass().getSimpleName();
        }
        return new ToolResponse(null, false, errorMessage, executionTimeMs, null);
    }

    /** Get the result of the tool execution. */
    public Object getResult() {
        return result;
    }

    /** Get the result with type casting. */
    @SuppressWarnings("unchecked")
    public <T> T getResult(Class<T> type) {
        if (result == null) {
            return null;
        }

        if (type.isAssignableFrom(result.getClass())) {
            return (T) result;
        }

        throw new ClassCastException(
                String.format(
                        "Cannot cast result of type %s to %s",
                        result.getClass().getSimpleName(), type.getSimpleName()));
    }

    /** Check if the tool execution was successful. */
    public boolean isSuccess() {
        return success;
    }

    /** Check if the tool execution failed. */
    public boolean isError() {
        return !success;
    }

    /** Get the error message if the execution failed. */
    public String getError() {
        return error;
    }

    /** Get the execution time in milliseconds. */
    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    /** Get the tool name if available. */
    public String getToolName() {
        return toolName;
    }

    /** Get the result as a string representation. */
    public String getResultAsString() {
        if (result == null) {
            return null;
        }
        return result.toString();
    }

    /**
     * Legacy method for backward compatibility.
     *
     * @deprecated Use getResultAsString() instead
     */
    @Deprecated
    public String responseData() {
        return getResultAsString();
    }

    /**
     * Legacy method for backward compatibility.
     *
     * @deprecated Use getToolName() instead
     */
    @Deprecated
    public String name() {
        return toolName;
    }

    /**
     * Legacy method for backward compatibility.
     *
     * @deprecated No direct replacement - use success/error status
     */
    @Deprecated
    public String id() {
        return toolName != null
                ? toolName + "_" + System.currentTimeMillis()
                : String.valueOf(System.currentTimeMillis());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolResponse that = (ToolResponse) o;
        return success == that.success
                && executionTimeMs == that.executionTimeMs
                && Objects.equals(result, that.result)
                && Objects.equals(error, that.error)
                && Objects.equals(toolName, that.toolName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, success, error, executionTimeMs, toolName);
    }

    @Override
    public String toString() {
        if (success) {
            return String.format(
                    "ToolResponse{success=true, result=%s, executionTime=%dms%s}",
                    result,
                    executionTimeMs,
                    toolName != null ? ", toolName='" + toolName + "'" : "");
        } else {
            return String.format(
                    "ToolResponse{success=false, error='%s', executionTime=%dms%s}",
                    error,
                    executionTimeMs,
                    toolName != null ? ", toolName='" + toolName + "'" : "");
        }
    }
}
