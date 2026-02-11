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

package org.apache.flink.agents.integrations.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for retry logic in {@link MCPServer}. */
class MCPRetryTest {

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retry on SocketTimeoutException and succeed")
    void testRetryOnSocketTimeout() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .maxBackoff(Duration.ofMillis(100))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new SocketTimeoutException("Connection timeout");
                    }
                    return "success";
                };

        String result = invokeExecuteWithRetry(server, operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retry on ConnectException and succeed")
    void testRetryOnConnectException() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw new ConnectException("Connection refused");
                    }
                    return "success";
                };

        String result = invokeExecuteWithRetry(server, operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retry on 503 Service Unavailable")
    void testRetryOn503Error() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new RuntimeException("HTTP 503 Service Unavailable");
                    }
                    return "success";
                };

        String result = invokeExecuteWithRetry(server, operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retry on 429 Too Many Requests")
    void testRetryOn429Error() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new RuntimeException("HTTP 429 Too Many Requests");
                    }
                    return "success";
                };

        String result = invokeExecuteWithRetry(server, operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("No retry on 4xx client errors")
    void testNoRetryOn4xxError() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("HTTP 400 Bad Request");
                };

        assertThatThrownBy(() -> invokeExecuteWithRetry(server, operation, "testOperation"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP operation 'testOperation' failed")
                .hasMessageContaining("400 Bad Request");

        // Should only try once (no retries for 4xx)
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Fail after max retries exceeded")
    void testFailAfterMaxRetries() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    throw new SocketTimeoutException("Always fails");
                };

        assertThatThrownBy(() -> invokeExecuteWithRetry(server, operation, "testOperation"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed after 2 retries");

        // Should try initial attempt + 2 retries = 3 total
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Exponential backoff timing")
    void testExponentialBackoff() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(3)
                        .initialBackoff(Duration.ofMillis(50))
                        .maxBackoff(Duration.ofMillis(500))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 4) {
                        throw new ConnectException("Connection failed");
                    }
                    return "success";
                };

        String result = invokeExecuteWithRetry(server, operation, "testOperation");
        long duration = System.currentTimeMillis() - startTime;

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(4);

        // Expected backoff: ~50ms + ~100ms + ~200ms = ~350ms (plus some jitter)
        assertThat(duration).isGreaterThan(300L).isLessThan(600L);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Max backoff limit respected")
    void testMaxBackoffLimit() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(5)
                        .initialBackoff(Duration.ofMillis(100))
                        .maxBackoff(Duration.ofMillis(200))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 6) {
                        throw new ConnectException("Connection failed");
                    }
                    return "success";
                };

        String result = invokeExecuteWithRetry(server, operation, "testOperation");
        long duration = System.currentTimeMillis() - startTime;

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(6);

        // With max backoff of 200ms, after first backoff (100ms), all subsequent should be ~200ms
        assertThat(duration).isGreaterThan(850L).isLessThan(1200L);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Immediate success without retry")
    void testImmediateSuccess() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                };

        long startTime = System.currentTimeMillis();
        String result = invokeExecuteWithRetry(server, operation, "testOperation");
        long duration = System.currentTimeMillis() - startTime;

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(1);
        // Should be very fast (no retries)
        assertThat(duration).isLessThan(50L);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("InterruptedException stops retry")
    void testInterruptedExceptionStopsRetry() throws Exception {
        MCPServer server =
                MCPServer.builder("http://localhost:8000")
                        .maxRetries(3)
                        .initialBackoff(Duration.ofMillis(1000)) // Long backoff
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new SocketTimeoutException("Timeout");
                    }
                    return "success";
                };

        // Start in a separate thread so we can interrupt it
        Thread testThread =
                new Thread(
                        () -> {
                            try {
                                invokeExecuteWithRetry(server, operation, "testOperation");
                            } catch (Exception e) {
                                // Expected
                            }
                        });

        testThread.start();
        Thread.sleep(50);
        testThread.interrupt();
        testThread.join(2000);

        assertThat(testThread.isAlive()).isFalse();
        assertThat(attempts.get()).isEqualTo(1); // Only first attempt
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Default retry configuration")
    void testDefaultRetryConfiguration() {
        MCPServer server = MCPServer.builder("http://localhost:8000").build();

        assertThat(server.getMaxRetries()).isEqualTo(3);
        assertThat(server.getInitialBackoffMs()).isEqualTo(100);
        assertThat(server.getMaxBackoffMs()).isEqualTo(10000);
    }

    /** Helper method to invoke the private executeWithRetry method via reflection. */
    @SuppressWarnings("unchecked")
    private <T> T invokeExecuteWithRetry(
            MCPServer server, Callable<T> operation, String operationName) throws Exception {
        Method method =
                MCPServer.class.getDeclaredMethod("executeWithRetry", Callable.class, String.class);
        method.setAccessible(true);
        try {
            return (T) method.invoke(server, operation, operationName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}
