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
package org.apache.flink.agents.runtime.actionstate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a result of a function call execution for fine-grained durable execution.
 *
 * <p>This class stores the execution result of a single {@code durable_execute} or {@code
 * durable_execute_async} call, enabling recovery without re-execution when the same call is
 * encountered during job recovery.
 *
 * <p>During recovery, the success or failure of the original call is determined by checking whether
 * {@code exceptionPayload} is null.
 */
public class CallResult {

    /** Persisted status of the durable call. */
    private enum Status {
        PENDING,
        SUCCEEDED,
        FAILED
    }

    /** Function identifier: module+qualname for Python, or method signature for Java. */
    private final String functionId;

    /** Stable digest of the serialized arguments for validation during recovery. */
    private final String argsDigest;

    /** Serialized return value of the function call (null if the call threw an exception). */
    private final byte[] resultPayload;

    /** Serialized exception info if the call failed (null if the call succeeded). */
    private final byte[] exceptionPayload;

    /** Persisted status of the durable call. Null indicates legacy state written before status. */
    @JsonProperty("status")
    private final Status status;

    /** Default constructor for deserialization. */
    public CallResult() {
        this.functionId = null;
        this.argsDigest = null;
        this.resultPayload = null;
        this.exceptionPayload = null;
        this.status = null;
    }

    /**
     * Constructs a CallResult for a successful function call.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @param resultPayload the serialized return value
     */
    public CallResult(String functionId, String argsDigest, byte[] resultPayload) {
        this.functionId = functionId;
        this.argsDigest = argsDigest;
        this.resultPayload = resultPayload;
        this.exceptionPayload = null;
        this.status = Status.SUCCEEDED;
    }

    /**
     * Constructs a CallResult with explicit result and exception payloads.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @param resultPayload the serialized return value (null if exception occurred)
     * @param exceptionPayload the serialized exception (null if call succeeded)
     */
    public CallResult(
            String functionId, String argsDigest, byte[] resultPayload, byte[] exceptionPayload) {
        this(
                functionId,
                argsDigest,
                resultPayload,
                exceptionPayload,
                exceptionPayload == null ? Status.SUCCEEDED : Status.FAILED);
    }

    /**
     * Constructs a CallResult with explicit result, exception payloads, and status.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @param resultPayload the serialized return value (null if exception occurred or pending)
     * @param exceptionPayload the serialized exception (null if call succeeded or pending)
     * @param status the persisted call status
     */
    private CallResult(
            String functionId,
            String argsDigest,
            byte[] resultPayload,
            byte[] exceptionPayload,
            Status status) {
        this.functionId = functionId;
        this.argsDigest = argsDigest;
        this.resultPayload = resultPayload;
        this.exceptionPayload = exceptionPayload;
        this.status = status;
    }

    /**
     * Creates a CallResult for an in-flight durable call whose terminal result has not yet been
     * persisted.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @return a new CallResult representing a pending call
     */
    public static CallResult pending(String functionId, String argsDigest) {
        return new CallResult(functionId, argsDigest, null, null, Status.PENDING);
    }

    public String getFunctionId() {
        return functionId;
    }

    public String getArgsDigest() {
        return argsDigest;
    }

    public byte[] getResultPayload() {
        return resultPayload;
    }

    public byte[] getExceptionPayload() {
        return exceptionPayload;
    }

    /**
     * Validates if this CallResult matches the given function identifier and arguments digest.
     *
     * @param functionId the function identifier to match
     * @param argsDigest the arguments digest to match
     * @return true if both functionId and argsDigest match, false otherwise
     */
    public boolean matches(String functionId, String argsDigest) {
        return Objects.equals(this.functionId, functionId)
                && Objects.equals(this.argsDigest, argsDigest);
    }

    /**
     * Checks if this call result represents a successful execution.
     *
     * @return true if the call succeeded (no exception), false otherwise
     */
    @JsonIgnore
    public boolean isSuccess() {
        return getEffectiveStatus() == Status.SUCCEEDED;
    }

    /** Checks if this call result represents a failed execution. */
    @JsonIgnore
    public boolean isFailure() {
        return getEffectiveStatus() == Status.FAILED;
    }

    /** Checks if this call result represents an in-flight execution. */
    @JsonIgnore
    public boolean isPending() {
        return getEffectiveStatus() == Status.PENDING;
    }

    /**
     * Creates a CallResult matching legacy persisted data where {@code status} was absent.
     *
     * <p>Used by backward-compatibility tests for legacy serialized state.
     */
    static CallResult ofNullStatus(
            String functionId, String argsDigest, byte[] resultPayload, byte[] exceptionPayload) {
        return new CallResult(functionId, argsDigest, resultPayload, exceptionPayload, null);
    }

    /**
     * Returns the effective status of the call result.
     *
     * <p>For legacy states written before {@code status} existed, the effective status is inferred
     * from {@code exceptionPayload}.
     */
    @JsonIgnore
    private Status getEffectiveStatus() {
        if (status != null) {
            return status;
        }
        return exceptionPayload == null ? Status.SUCCEEDED : Status.FAILED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CallResult that = (CallResult) o;
        return Objects.equals(functionId, that.functionId)
                && Objects.equals(argsDigest, that.argsDigest)
                && Arrays.equals(resultPayload, that.resultPayload)
                && Arrays.equals(exceptionPayload, that.exceptionPayload)
                && status == that.status;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(functionId, argsDigest, status);
        result = 31 * result + Arrays.hashCode(resultPayload);
        result = 31 * result + Arrays.hashCode(exceptionPayload);
        return result;
    }

    @Override
    public String toString() {
        return "CallResult{"
                + "functionId='"
                + functionId
                + '\''
                + ", argsDigest='"
                + argsDigest
                + '\''
                + ", resultPayload="
                + (resultPayload != null ? resultPayload.length + " bytes" : "null")
                + ", exceptionPayload="
                + (exceptionPayload != null ? exceptionPayload.length + " bytes" : "null")
                + ", status="
                + status
                + '}';
    }
}
