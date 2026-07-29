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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;

/**
 * Outcome of a {@link Subagent} call.
 *
 * <p>Sub-agent implementations should capture internal failures into a {@code Result} (via {@link
 * #error}) instead of throwing, so callers can inspect {@link #isSuccess()} without try/catch.
 *
 * <p>The failure cause is carried as a serializable {@code errorMessage} — the full stack trace of
 * the failure — rather than a live exception, so that a {@code Result} can be persisted through
 * durable execution.
 */
public class Result implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final Object result;
    private final String errorMessage;

    @JsonCreator
    public Result(
            @JsonProperty("success") boolean success,
            @JsonProperty("result") Object result,
            @JsonProperty("errorMessage") String errorMessage) {
        this.success = success;
        this.result = result;
        this.errorMessage = errorMessage;
    }

    /** Creates a successful result carrying the given value. */
    public static Result ok(Object result) {
        return new Result(true, result, null);
    }

    /** Creates a failed result carrying the full stack trace of the given exception. */
    public static Result error(Exception exception) {
        return new Result(false, null, exception == null ? null : stackTraceOf(exception));
    }

    /** Creates a failed result carrying the given message. */
    public static Result error(String errorMessage) {
        return new Result(false, null, errorMessage);
    }

    private static String stackTraceOf(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getResult() {
        return result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Reconstructs an exception carrying the stored stack trace as its message, or null if this
     * result is successful.
     */
    @JsonIgnore
    public Exception getException() {
        return success ? null : new RuntimeException(errorMessage);
    }
}
