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

package org.apache.flink.agents.runtime.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Unit tests for {@link RunnerContextImpl.DurableExecutionContext}. */
class DurableExecutionContextTest {

    private ActionState actionState;
    private AtomicInteger persistCallCount;
    private ActionState lastPersistedState;
    private Object testKey;
    private long testSequenceNumber;
    private Action mockAction;
    private Event mockEvent;

    public static final class NoPublicStringConstructorException extends Exception {
        public NoPublicStringConstructorException() {
            super();
        }

        private NoPublicStringConstructorException(String message) {
            super(message);
        }

        static NoPublicStringConstructorException withMessage(String message) {
            return new NoPublicStringConstructorException(message);
        }
    }

    @BeforeEach
    void setUp() {
        actionState = new ActionState(null);
        persistCallCount = new AtomicInteger(0);
        lastPersistedState = null;
        testKey = "testKey";
        testSequenceNumber = 1L;
        mockAction = mock(Action.class);
        mockEvent = mock(Event.class);
    }

    private RunnerContextImpl.DurableExecutionContext createContext() {
        ActionStatePersister persister =
                (key, seqNum, action, event, state) -> {
                    persistCallCount.incrementAndGet();
                    lastPersistedState = state;
                };
        return new RunnerContextImpl.DurableExecutionContext(
                testKey, testSequenceNumber, mockAction, mockEvent, actionState, persister);
    }

    @Test
    void testInitialization() {
        actionState.addCallResult(
                new CallResult("funcA", "digestA", "resultA".getBytes(StandardCharsets.UTF_8)));
        actionState.addCallResult(
                new CallResult("funcB", "digestB", "resultB".getBytes(StandardCharsets.UTF_8)));

        RunnerContextImpl.DurableExecutionContext context = createContext();

        assertEquals(0, context.getCurrentCallIndex());
        assertSame(actionState, context.getActionState());
    }

    @Test
    void testMatchNextOrClearSubsequentCallResultHit() {
        byte[] expectedResult = "cached_result".getBytes(StandardCharsets.UTF_8);
        actionState.addCallResult(new CallResult("funcA", "digestA", expectedResult));

        RunnerContextImpl.DurableExecutionContext context = createContext();

        Object[] result = context.matchNextOrClearSubsequentCallResult("funcA", "digestA");

        assertNotNull(result);
        assertEquals(3, result.length);
        assertTrue((Boolean) result[0]); // isHit
        assertArrayEquals(expectedResult, (byte[]) result[1]); // resultPayload
        assertNull(result[2]); // exceptionPayload
        assertEquals(1, context.getCurrentCallIndex());
    }

    @Test
    void testMatchNextOrClearSubsequentCallResultMiss() {
        RunnerContextImpl.DurableExecutionContext context = createContext();

        Object[] result = context.matchNextOrClearSubsequentCallResult("funcA", "digestA");

        assertNull(result);
        assertEquals(0, context.getCurrentCallIndex());
    }

    @Test
    void testMatchNextOrClearSubsequentCallResultPendingTreatedAsMiss() {
        actionState.addCallResult(CallResult.pending("funcA", "digestA"));

        RunnerContextImpl.DurableExecutionContext context = createContext();

        Object[] result = context.matchNextOrClearSubsequentCallResult("funcA", "digestA");

        assertNull(result);
        assertEquals(0, context.getCurrentCallIndex());
        assertTrue(actionState.getCallResults().get(0).isPending());
    }

    @Test
    void testMatchNextOrClearSubsequentCallResultMismatch() {
        actionState.addCallResult(new CallResult("funcA", "digestA", "result".getBytes()));
        actionState.addCallResult(new CallResult("funcB", "digestB", "result".getBytes()));

        RunnerContextImpl.DurableExecutionContext context = createContext();

        // Call with mismatched functionId - should clear subsequent results and return null
        Object[] result = context.matchNextOrClearSubsequentCallResult("funcX", "digestX");

        assertNull(result);
        // ActionState should have results cleared from index 0
        assertEquals(0, actionState.getCallResultCount());
        // Persist is not called here - it will be called in recordCallCompletion
        assertEquals(0, persistCallCount.get());
    }

    @Test
    void testRecordCallCompletionSuccess() {
        RunnerContextImpl.DurableExecutionContext context = createContext();

        byte[] resultPayload = "success_result".getBytes(StandardCharsets.UTF_8);
        context.recordCallCompletion("funcA", "digestA", resultPayload, null);

        assertEquals(1, context.getCurrentCallIndex());
        assertEquals(1, actionState.getCallResults().size());
        assertEquals("funcA", actionState.getCallResults().get(0).getFunctionId());
        // Verify persister was called
        assertEquals(1, persistCallCount.get());
        assertSame(actionState, lastPersistedState);
    }

    @Test
    void testRecordCallCompletionException() {
        RunnerContextImpl.DurableExecutionContext context = createContext();

        byte[] exceptionPayload = "exception_data".getBytes(StandardCharsets.UTF_8);
        context.recordCallCompletion("funcA", "digestA", null, exceptionPayload);

        assertEquals(1, context.getCurrentCallIndex());
        CallResult recorded = actionState.getCallResults().get(0);
        assertNull(recorded.getResultPayload());
        assertArrayEquals(exceptionPayload, recorded.getExceptionPayload());
        assertEquals(1, persistCallCount.get());
    }

    @Test
    void testMultipleCallResultRecovery() {
        byte[] result1 = "result1".getBytes(StandardCharsets.UTF_8);
        byte[] result2 = "result2".getBytes(StandardCharsets.UTF_8);
        actionState.addCallResult(new CallResult("func1", "digest1", result1));
        actionState.addCallResult(new CallResult("func2", "digest2", result2));

        RunnerContextImpl.DurableExecutionContext context = createContext();

        // First call should hit
        Object[] hit1 = context.matchNextOrClearSubsequentCallResult("func1", "digest1");
        assertNotNull(hit1);
        assertTrue((Boolean) hit1[0]);
        assertArrayEquals(result1, (byte[]) hit1[1]);

        // Second call should hit
        Object[] hit2 = context.matchNextOrClearSubsequentCallResult("func2", "digest2");
        assertNotNull(hit2);
        assertTrue((Boolean) hit2[0]);
        assertArrayEquals(result2, (byte[]) hit2[1]);

        // Third call should miss (no more results)
        Object[] miss = context.matchNextOrClearSubsequentCallResult("func3", "digest3");
        assertNull(miss);
    }

    @Test
    void testRecoveryWithExceptionPayload() {
        byte[] exceptionPayload = "exception_data".getBytes(StandardCharsets.UTF_8);
        actionState.addCallResult(new CallResult("funcA", "digestA", null, exceptionPayload));

        RunnerContextImpl.DurableExecutionContext context = createContext();

        Object[] result = context.matchNextOrClearSubsequentCallResult("funcA", "digestA");

        assertNotNull(result);
        assertTrue((Boolean) result[0]); // isHit
        assertNull(result[1]); // resultPayload should be null
        assertArrayEquals(exceptionPayload, (byte[]) result[2]); // exceptionPayload
    }

    @Test
    void testMultiplePersistCalls() {
        RunnerContextImpl.DurableExecutionContext context = createContext();

        // Record multiple completions
        context.recordCallCompletion("func1", "digest1", "result1".getBytes(), null);
        context.recordCallCompletion("func2", "digest2", "result2".getBytes(), null);
        context.recordCallCompletion("func3", "digest3", "result3".getBytes(), null);

        // Each call should trigger persistence
        assertEquals(3, persistCallCount.get());
        assertEquals(3, actionState.getCallResults().size());
    }

    // ==================== DurableExecutionException Tests ====================

    @Test
    void testDurableExecutionExceptionSerialization() throws Exception {
        // Create exception
        RuntimeException original = new RuntimeException("Test error message");
        RunnerContextImpl.DurableExecutionException durableException =
                RunnerContextImpl.DurableExecutionException.fromException(original);

        // Serialize to JSON
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(durableException);

        // Verify JSON field names are semantically correct
        JsonNode node = mapper.readTree(json);
        assertEquals(
                "java.lang.RuntimeException",
                node.get("exceptionClass").asText(),
                "JSON field 'exceptionClass' should contain the exception class name");
        assertEquals(
                "Test error message",
                node.get("message").asText(),
                "JSON field 'message' should contain the error message");
    }

    @Test
    void testDurableExecutionExceptionPreservesTimeoutExceptionType() throws Exception {
        java.util.concurrent.TimeoutException original =
                new java.util.concurrent.TimeoutException("batch timed out");
        RunnerContextImpl.DurableExecutionException durableException =
                RunnerContextImpl.DurableExecutionException.fromException(original);

        ObjectMapper mapper = new ObjectMapper();
        RunnerContextImpl.DurableExecutionException deserialized =
                mapper.readValue(
                        mapper.writeValueAsBytes(durableException),
                        RunnerContextImpl.DurableExecutionException.class);

        Exception recovered = deserialized.toException();
        assertInstanceOf(java.util.concurrent.TimeoutException.class, recovered);
        assertEquals("batch timed out", recovered.getMessage());
    }

    @Test
    void testDurableExecutionExceptionDeserialization() throws Exception {
        // Create and serialize exception
        IllegalArgumentException original = new IllegalArgumentException("Invalid argument: foo");
        RunnerContextImpl.DurableExecutionException durableException =
                RunnerContextImpl.DurableExecutionException.fromException(original);

        ObjectMapper mapper = new ObjectMapper();
        byte[] serialized = mapper.writeValueAsBytes(durableException);

        // Deserialize
        RunnerContextImpl.DurableExecutionException deserialized =
                mapper.readValue(serialized, RunnerContextImpl.DurableExecutionException.class);

        // Convert back to exception and verify content
        Exception recovered = deserialized.toException();
        assertInstanceOf(IllegalArgumentException.class, recovered);
        assertEquals("Invalid argument: foo", recovered.getMessage());
    }

    @Test
    void testDurableExecutionExceptionRoundTrip() throws Exception {
        // Test various exception types
        Exception[] testExceptions = {
            new RuntimeException("Runtime error"),
            new IllegalStateException("Illegal state"),
            new NullPointerException("Null value"),
            new RuntimeException("Message with special chars: \"quotes\" and 'apostrophes'"),
            new RuntimeException("") // Empty message
        };

        ObjectMapper mapper = new ObjectMapper();

        for (Exception original : testExceptions) {
            // Create DurableExecutionException
            RunnerContextImpl.DurableExecutionException durableException =
                    RunnerContextImpl.DurableExecutionException.fromException(original);

            // Serialize and deserialize
            byte[] serialized = mapper.writeValueAsBytes(durableException);
            RunnerContextImpl.DurableExecutionException deserialized =
                    mapper.readValue(serialized, RunnerContextImpl.DurableExecutionException.class);

            // Verify round-trip
            Exception recovered = deserialized.toException();
            assertInstanceOf(original.getClass(), recovered);
            if (original.getMessage() != null && !original.getMessage().isEmpty()) {
                assertEquals(original.getMessage(), recovered.getMessage());
            }
        }
    }

    @Test
    void testDurableExecutionExceptionWithNullMessage() throws Exception {
        // Create exception with null message
        RuntimeException original = new RuntimeException((String) null);
        RunnerContextImpl.DurableExecutionException durableException =
                RunnerContextImpl.DurableExecutionException.fromException(original);

        ObjectMapper mapper = new ObjectMapper();
        byte[] serialized = mapper.writeValueAsBytes(durableException);

        // Should not throw during serialization/deserialization
        RunnerContextImpl.DurableExecutionException deserialized =
                mapper.readValue(serialized, RunnerContextImpl.DurableExecutionException.class);

        Exception recovered = deserialized.toException();
        assertInstanceOf(RuntimeException.class, recovered);
        assertNull(recovered.getMessage());
    }

    @Test
    void testDurableExecutionExceptionFallbackPreservesMessageWhenStringConstructorMissing()
            throws Exception {
        NoPublicStringConstructorException original =
                NoPublicStringConstructorException.withMessage("durable failure");
        RunnerContextImpl.DurableExecutionException durableException =
                RunnerContextImpl.DurableExecutionException.fromException(original);

        Exception recovered = durableException.toException();

        assertInstanceOf(RuntimeException.class, recovered);
        assertEquals(
                NoPublicStringConstructorException.class.getName() + ": durable failure",
                recovered.getMessage());
    }

    @Test
    void testDurableExecutionExceptionUsesContextClassLoader(@TempDir Path tempDir)
            throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Path packageDir = sourceRoot.resolve("usercode");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve("TcclOnlyException.java");
        Files.writeString(
                sourceFile,
                "package usercode;\n"
                        + "public class TcclOnlyException extends Exception {\n"
                        + "    public TcclOnlyException(String message) {\n"
                        + "        super(message);\n"
                        + "    }\n"
                        + "}\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for this test");
        assertEquals(
                0,
                compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString()));

        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader userCodeLoader =
                new URLClassLoader(new URL[] {classesDir.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(userCodeLoader);
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("usercode.TcclOnlyException"));

            RunnerContextImpl.DurableExecutionException durableException =
                    new RunnerContextImpl.DurableExecutionException(
                            "usercode.TcclOnlyException", "visible through TCCL");

            Exception recovered = durableException.toException();

            assertEquals("usercode.TcclOnlyException", recovered.getClass().getName());
            assertSame(userCodeLoader, recovered.getClass().getClassLoader());
            assertEquals("visible through TCCL", recovered.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
    }
}
