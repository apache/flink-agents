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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * A reusable utility for executing operations with retry logic and binary exponential backoff.
 *
 * <p>By default, the following exceptions are considered retryable:
 *
 * <ul>
 *   <li>{@link SocketTimeoutException}
 *   <li>{@link ConnectException}
 *   <li>Exceptions whose message contains "HTTP 503" or "HTTP 429"
 *   <li>Exceptions whose message contains "Connection reset", "Connection refused", or "Connection
 *       timed out"
 * </ul>
 *
 * <p>A custom retryable predicate can be provided to override this behavior.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RetryExecutor executor = RetryExecutor.builder()
 *     .maxRetries(3)
 *     .initialBackoffMs(100)
 *     .maxBackoffMs(10000)
 *     .build();
 *
 * String result = executor.execute(() -> callRemoteService(), "callRemoteService");
 * }</pre>
 */
public class RetryExecutor {

    private static final Random RANDOM = new Random();

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_INITIAL_BACKOFF_MS = 100;
    private static final long DEFAULT_MAX_BACKOFF_MS = 10000;

    private final int maxRetries;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Predicate<Exception> retryablePredicate;

    private RetryExecutor(
            int maxRetries,
            long initialBackoffMs,
            long maxBackoffMs,
            Predicate<Exception> retryablePredicate) {
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.retryablePredicate =
                retryablePredicate != null ? retryablePredicate : RetryExecutor::isRetryableDefault;
    }

    /** Creates a builder for {@link RetryExecutor}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a {@link RetryExecutor} with default settings. */
    public static RetryExecutor withDefaults() {
        return builder().build();
    }

    /**
     * Execute an operation with retry logic.
     *
     * @param operation The operation to execute
     * @param operationName Name of the operation for error messages
     * @return The result of the operation
     * @throws RuntimeException if all retries fail or a non-retryable exception occurs
     */
    public <T> T execute(Callable<T> operation, String operationName) {
        int attempt = 0;
        long window = initialBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                return operation.call();
            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (!retryablePredicate.test(e)) {
                    throw new RuntimeException(
                            String.format(
                                    "Operation '%s' failed: %s", operationName, e.getMessage()),
                            e);
                }

                if (attempt > maxRetries) {
                    break;
                }

                // Binary Exponential Backoff: random wait from [0, window]
                try {
                    long sleepTime = (long) (RANDOM.nextDouble() * (window + 1));
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Interrupted while retrying operation: " + operationName, ie);
                }

                window = Math.min(window * 2, maxBackoffMs);
            }
        }

        throw new RuntimeException(
                String.format(
                        "Operation '%s' failed after %d retries: %s",
                        operationName, maxRetries, lastException.getMessage()),
                lastException);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    /**
     * Default retryable check.
     *
     * @param e The exception to check
     * @return true if the operation should be retried
     */
    static boolean isRetryableDefault(Exception e) {
        if (e instanceof SocketTimeoutException || e instanceof ConnectException) {
            return true;
        }
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("HTTP 503") || message.contains("HTTP 429")) {
                return true;
            }
            return message.contains("Connection reset")
                    || message.contains("Connection refused")
                    || message.contains("Connection timed out");
        }
        return false;
    }

    /** Builder for {@link RetryExecutor}. */
    public static class Builder {
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long initialBackoffMs = DEFAULT_INITIAL_BACKOFF_MS;
        private long maxBackoffMs = DEFAULT_MAX_BACKOFF_MS;
        private Predicate<Exception> retryablePredicate;

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
            return this;
        }

        public Builder maxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
            return this;
        }

        public Builder retryablePredicate(Predicate<Exception> retryablePredicate) {
            this.retryablePredicate = retryablePredicate;
            return this;
        }

        public RetryExecutor build() {
            return new RetryExecutor(
                    maxRetries, initialBackoffMs, maxBackoffMs, retryablePredicate);
        }
    }
}
