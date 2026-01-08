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

    /** Function identifier: module+qualname for Python, or method signature for Java. */
    private final String functionId;

    /** Stable digest of the serialized arguments for validation during recovery. */
    private final String argsDigest;

    /** Serialized return value of the function call (null if the call threw an exception). */
    private final byte[] resultPayload;

    /** Serialized exception info if the call failed (null if the call succeeded). */
    private final byte[] exceptionPayload;

    /** Default constructor for deserialization. */
    public CallResult() {
        this.functionId = null;
        this.argsDigest = null;
        this.resultPayload = null;
        this.exceptionPayload = null;
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
        this.functionId = functionId;
        this.argsDigest = argsDigest;
        this.resultPayload = resultPayload;
        this.exceptionPayload = exceptionPayload;
    }

    /**
     * Creates a CallResult for a failed function call.
     *
     * @param functionId the function identifier
     * @param argsDigest the digest of serialized arguments
     * @param exceptionPayload the serialized exception
     * @return a new CallResult representing a failed call
     */
    public static CallResult ofException(
            String functionId, String argsDigest, byte[] exceptionPayload) {
        return new CallResult(functionId, argsDigest, null, exceptionPayload);
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
     * Checks if this call result represents a successful execution.
     *
     * @return true if the call succeeded (no exception), false otherwise
     */
    @JsonIgnore
    public boolean isSuccess() {
        return exceptionPayload == null;
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
                && Arrays.equals(exceptionPayload, that.exceptionPayload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(functionId, argsDigest);
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
                + '}';
    }
}
