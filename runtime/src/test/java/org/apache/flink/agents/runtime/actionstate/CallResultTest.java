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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link CallResult}. */
public class CallResultTest {

    @Test
    public void testSuccessfulCallResult() {
        String functionId = "my_module.my_function";
        byte[] resultPayload = "result".getBytes();

        CallResult result = new CallResult(functionId, resultPayload);

        assertEquals(functionId, result.getFunctionId());
        assertArrayEquals(resultPayload, result.getResultPayload());
        assertNull(result.getExceptionPayload());
        assertTrue(result.isSuccess());
    }

    @Test
    public void testFailedCallResult() {
        String functionId = "my_module.my_function";
        byte[] exceptionPayload = "exception".getBytes();

        CallResult result = new CallResult(functionId, null, exceptionPayload);

        assertEquals(functionId, result.getFunctionId());
        assertNull(result.getResultPayload());
        assertArrayEquals(exceptionPayload, result.getExceptionPayload());
        assertTrue(result.isFailure());
    }

    @Test
    public void testFullConstructor() {
        String functionId = "my_module.my_function";
        byte[] resultPayload = "result".getBytes();
        byte[] exceptionPayload = null;

        CallResult result = new CallResult(functionId, resultPayload, exceptionPayload);

        assertEquals(functionId, result.getFunctionId());
        assertArrayEquals(resultPayload, result.getResultPayload());
        assertNull(result.getExceptionPayload());
        assertTrue(result.isSuccess());
    }

    @Test
    public void testPendingCallResult() {
        CallResult result = CallResult.pending("my_module.my_function");

        assertNull(result.getResultPayload());
        assertNull(result.getExceptionPayload());
        assertTrue(result.isPending());
    }

    @Test
    public void testLegacyStatusInference() {
        CallResult success =
                CallResult.ofNullStatus("my_module.my_function", "result".getBytes(), null);
        CallResult failure =
                CallResult.ofNullStatus("my_module.my_function", null, "exception".getBytes());

        assertTrue(success.isSuccess());
        assertTrue(failure.isFailure());
    }

    @Test
    public void testMatches() {
        String functionId = "my_module.my_function";
        byte[] resultPayload = "result".getBytes();

        CallResult result = new CallResult(functionId, resultPayload);

        assertTrue(result.matches(functionId));
        assertFalse(result.matches("other_function"));
        assertTrue(result.matches(functionId));
        assertFalse(result.matches("other_function"));
    }

    @Test
    public void testMatchesWithNullValues() {
        CallResult result = new CallResult();

        assertTrue(result.matches(null));
        assertFalse(result.matches("function"));
        assertTrue(result.matches(null));
    }

    @Test
    public void testEquals() {
        String functionId = "my_module.my_function";
        byte[] resultPayload = "result".getBytes();

        CallResult result1 = new CallResult(functionId, resultPayload);
        CallResult result2 = new CallResult(functionId, resultPayload);
        CallResult result3 = new CallResult("other", resultPayload);

        assertEquals(result1, result2);
        assertNotEquals(result1, result3);
        assertNotEquals(result1, null);
        assertNotEquals(result1, "string");
        assertEquals(result1, result1);
    }

    @Test
    public void testHashCode() {
        String functionId = "my_module.my_function";
        byte[] resultPayload = "result".getBytes();

        CallResult result1 = new CallResult(functionId, resultPayload);
        CallResult result2 = new CallResult(functionId, resultPayload);

        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    public void testToString() {
        String functionId = "my_module.my_function";
        byte[] resultPayload = "result".getBytes();

        CallResult result = new CallResult(functionId, resultPayload);
        String str = result.toString();

        assertTrue(str.contains(functionId));
        assertTrue(str.contains("bytes"));
    }

    @Test
    public void testToStringWithNullPayloads() {
        CallResult result = new CallResult("func", null, null);
        String str = result.toString();

        assertTrue(str.contains("null"));
        assertTrue(str.contains("SUCCEEDED"));
    }

    @Test
    public void testDefaultConstructor() {
        CallResult result = new CallResult();

        assertNull(result.getFunctionId());
        assertNull(result.getResultPayload());
        assertNull(result.getExceptionPayload());
        assertTrue(result.isSuccess()); // exceptionPayload is null
    }
}
