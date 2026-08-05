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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Outcome of a {@link Subagent} call.
 *
 * <p>Sub-agent implementations should capture internal failures into a {@code Result} (via {@link
 * #error}) instead of throwing, so callers can inspect {@link #isSuccess()} without try/catch.
 *
 * <p>The failure cause is carried as a serializable {@code errorMessage} — the exception's type and
 * message — rather than a live exception, so that a {@code Result} can be persisted through durable
 * execution. The full stack trace is logged when the failure is captured, not persisted.
 */
public class Result implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(Result.class);

    private static final long serialVersionUID = 1L;

    /**
     * Plain mapper matching the durable-recovery binding: without polymorphic typing, so a
     * persisted payload re-binds as generic maps and lists.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    /**
     * Creates a failed result carrying the exception's type and message. The full stack trace is
     * logged here rather than persisted, keeping the durable payload bounded.
     */
    public static Result error(Exception exception) {
        if (exception == null) {
            return new Result(false, null, null);
        }
        LOG.warn("Sub-agent call failed; persisting the exception summary.", exception);
        return new Result(false, null, summaryOf(exception));
    }

    /** Creates a failed result carrying the given message. */
    public static Result error(String errorMessage) {
        return new Result(false, null, errorMessage);
    }

    private static String summaryOf(Exception exception) {
        return exception.getClass().getName() + ": " + exception.getMessage();
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getResult() {
        return result;
    }

    /**
     * Returns the payload converted to {@code resultClass}.
     *
     * <p>Durable recovery re-binds the persisted payload through a plain {@link ObjectMapper}
     * without polymorphic typing, so after a failover replay {@link #getResult()} hands back a
     * {@code LinkedHashMap} instead of the caller's type; this accessor converts the payload to the
     * expected class uniformly on both the first execution and a replay.
     */
    public <T> T getResult(Class<T> resultClass) {
        return OBJECT_MAPPER.convertValue(result, resultClass);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Reconstructs an exception carrying the stored summary as its message, or null if this result
     * is successful.
     */
    @JsonIgnore
    public Exception getException() {
        return success ? null : new RuntimeException(errorMessage);
    }
}
