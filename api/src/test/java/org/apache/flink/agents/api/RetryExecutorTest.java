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

package org.apache.flink.agents.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link RetryExecutor}. */
class RetryExecutorTest {

    @Test
    @DisplayName("Immediate success without retry")
    void testImmediateSuccess() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(3).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                };

        String result = executor.execute(operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Retry on SocketTimeoutException and succeed")
    void testRetryOnSocketTimeout() {
        RetryExecutor executor =
                RetryExecutor.builder()
                        .maxRetries(3)
                        .initialBackoffMs(10)
                        .maxBackoffMs(100)
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

        String result = executor.execute(operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Retry on ConnectException and succeed")
    void testRetryOnConnectException() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(2).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw new ConnectException("Connection refused");
                    }
                    return "success";
                };

        String result = executor.execute(operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Retry on HTTP 503 Service Unavailable")
    void testRetryOn503Error() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(2).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new RuntimeException("HTTP 503 Service Unavailable");
                    }
                    return "success";
                };

        String result = executor.execute(operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Retry on HTTP 429 Too Many Requests")
    void testRetryOn429Error() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(2).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new RuntimeException("HTTP 429 Too Many Requests");
                    }
                    return "success";
                };

        String result = executor.execute(operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("No retry on non-retryable exception")
    void testNoRetryOnNonRetryableException() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(3).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("Invalid input");
                };

        assertThatThrownBy(() -> executor.execute(operation, "testOperation"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Operation 'testOperation' failed")
                .hasMessageContaining("Invalid input");

        // Should only try once (no retries for non-retryable)
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("No retry on 4xx client errors")
    void testNoRetryOn4xxError() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(3).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("HTTP 400 Bad Request");
                };

        assertThatThrownBy(() -> executor.execute(operation, "testOperation"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Operation 'testOperation' failed")
                .hasMessageContaining("400 Bad Request");

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Fail after max retries exceeded")
    void testFailAfterMaxRetries() {
        RetryExecutor executor = RetryExecutor.builder().maxRetries(2).initialBackoffMs(10).build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    attempts.incrementAndGet();
                    throw new SocketTimeoutException("Always fails");
                };

        assertThatThrownBy(() -> executor.execute(operation, "testOperation"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed after 2 retries");

        // Should try initial attempt + 2 retries
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("InterruptedException stops retry")
    void testInterruptedExceptionStopsRetry() throws Exception {
        RetryExecutor executor =
                RetryExecutor.builder()
                        .maxRetries(3)
                        .initialBackoffMs(1000) // Long backoff
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

        Thread testThread =
                new Thread(
                        () -> {
                            try {
                                executor.execute(operation, "testOperation");
                            } catch (Exception e) {
                                // Expected
                            }
                        });

        testThread.start();
        Thread.sleep(50);
        testThread.interrupt();
        testThread.join(2000);

        assertThat(testThread.isAlive()).isFalse();
        // The thread should have been interrupted before exhausting all retries
        assertThat(attempts.get()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Default configuration values")
    void testDefaultConfiguration() {
        RetryExecutor executor = RetryExecutor.withDefaults();

        assertThat(executor.getMaxRetries()).isEqualTo(3);
        assertThat(executor.getInitialBackoffMs()).isEqualTo(100);
        assertThat(executor.getMaxBackoffMs()).isEqualTo(10000);
    }

    @Test
    @DisplayName("Custom retryable predicate")
    void testCustomRetryablePredicate() {
        RetryExecutor executor =
                RetryExecutor.builder()
                        .maxRetries(2)
                        .initialBackoffMs(10)
                        .retryablePredicate(e -> e instanceof IllegalStateException)
                        .build();

        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> operation =
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw new IllegalStateException("Temporary error");
                    }
                    return "success";
                };

        String result = executor.execute(operation, "testOperation");

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }
}
