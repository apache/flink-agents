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

package org.apache.flink.agents.api.media;

import org.apache.flink.agents.api.configuration.DurationUtils;
import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.nio.channels.ClosedByInterruptException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Shared interruptible polling implementation for {@link AsyncJobModel}. */
@Internal
public final class AsyncJobPolling {
    private static final String WAIT_INTERRUPTED_MESSAGE = "Media job wait interrupted";
    private static final int MAX_CAUSE_DEPTH = 64;

    private AsyncJobPolling() {}

    /** Injectable wait primitive so polling timeout tests can advance a virtual clock. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    /** Validate that {@code duration} is non-null and strictly positive. */
    public static Duration validatePositiveDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (!DurationUtils.isPositive(duration)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    /** Poll {@code fetcher} until terminal status, timeout, or thread interruption. */
    public static <Res extends MediaResult<?>> Res wait(
            Function<MediaJob, MediaJobState<Res>> fetcher,
            MediaJob job,
            @Nullable Duration timeout,
            Duration pollInterval) {
        return wait(fetcher, job, timeout, pollInterval, failure -> false);
    }

    /**
     * Poll {@code fetcher} until terminal status, timeout, or thread interruption, preserving any
     * failure matched by {@code nonCancellableFailure} even when the thread is interrupted.
     */
    public static <Res extends MediaResult<?>> Res wait(
            Function<MediaJob, MediaJobState<Res>> fetcher,
            MediaJob job,
            @Nullable Duration timeout,
            Duration pollInterval,
            Predicate<Throwable> nonCancellableFailure) {
        return wait(
                fetcher,
                job,
                timeout,
                pollInterval,
                System::nanoTime,
                AsyncJobPolling::sleepReal,
                nonCancellableFailure);
    }

    /**
     * Poll {@code fetcher} using the supplied clock and wait primitive until terminal status,
     * timeout, or interruption.
     */
    static <Res extends MediaResult<?>> Res wait(
            Function<MediaJob, MediaJobState<Res>> fetcher,
            MediaJob job,
            @Nullable Duration timeout,
            Duration pollInterval,
            LongSupplier nanoClock,
            Sleeper sleeper) {
        return wait(fetcher, job, timeout, pollInterval, nanoClock, sleeper, failure -> false);
    }

    static <Res extends MediaResult<?>> Res wait(
            Function<MediaJob, MediaJobState<Res>> fetcher,
            MediaJob job,
            @Nullable Duration timeout,
            Duration pollInterval,
            LongSupplier nanoClock,
            Sleeper sleeper,
            Predicate<Throwable> nonCancellableFailure) {
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(job, "job");
        if (timeout != null) {
            validatePositiveDuration(timeout, "timeout");
        }
        validatePositiveDuration(pollInterval, "pollInterval");
        Objects.requireNonNull(nanoClock, "nanoClock");
        Objects.requireNonNull(sleeper, "sleeper");
        Objects.requireNonNull(nonCancellableFailure, "nonCancellableFailure");

        long startedAtNanos = nanoClock.getAsLong();
        MediaJobStatus status = MediaJobStatus.UNKNOWN;
        while (true) {
            checkNotInterrupted();
            if (timeout != null
                    && elapsedSince(startedAtNanos, nanoClock).compareTo(timeout) >= 0) {
                throw new MediaJobTimeoutException(job, status, timeout, null);
            }
            MediaJobState<Res> state = fetchInterruptibly(fetcher, job, nonCancellableFailure);
            checkNotInterrupted();
            status = state.getStatus();
            if (status == MediaJobStatus.SUCCEEDED) {
                Res result = state.getResult();
                if (result == null) {
                    throw new MediaJobException(
                            job,
                            status,
                            null,
                            "Media job " + job.getJobId() + " succeeded without a result.");
                }
                return result;
            }
            if (status == MediaJobStatus.FAILED
                    || status == MediaJobStatus.CANCELED
                    || status == MediaJobStatus.UNKNOWN) {
                throw new MediaJobException(
                        job, status, state.getErrorCode(), state.getErrorMessage());
            }
            Duration delay = pollInterval;
            if (timeout != null) {
                Duration remaining = timeout.minus(elapsedSince(startedAtNanos, nanoClock));
                if (remaining.compareTo(delay) < 0) {
                    delay = remaining;
                }
            }
            if (DurationUtils.isPositive(delay)) {
                sleepInterruptibly(delay, sleeper);
            }
        }
    }

    /** Sleep for {@code delay}, preserving the nanosecond remainder. */
    static void sleepReal(Duration delay) throws InterruptedException {
        Thread.sleep(delay.toMillis(), delay.getNano() % 1_000_000);
    }

    private static <Res extends MediaResult<?>> MediaJobState<Res> fetchInterruptibly(
            Function<MediaJob, MediaJobState<Res>> fetcher,
            MediaJob job,
            Predicate<Throwable> nonCancellableFailure) {
        try {
            return fetcher.apply(job);
        } catch (RuntimeException failure) {
            if (nonCancellableFailure.test(failure)) {
                throw failure;
            }
            if (containsInterruption(failure)) {
                Thread.currentThread().interrupt();
                if (failure instanceof CancellationException) {
                    throw (CancellationException) failure;
                }
                CancellationException cancellation =
                        new CancellationException(WAIT_INTERRUPTED_MESSAGE);
                cancellation.initCause(failure);
                throw cancellation;
            }
            if (failure instanceof CancellationException) {
                throw (CancellationException) failure;
            }
            if (Thread.currentThread().isInterrupted()) {
                CancellationException cancellation =
                        new CancellationException(WAIT_INTERRUPTED_MESSAGE);
                cancellation.initCause(new InterruptedException(WAIT_INTERRUPTED_MESSAGE));
                cancellation.addSuppressed(failure);
                throw cancellation;
            }
            throw failure;
        }
    }

    private static boolean containsInterruption(Throwable failure) {
        int depth = 0;
        for (Throwable current = failure;
                current != null && depth < MAX_CAUSE_DEPTH;
                current = current.getCause(), depth++) {
            if (current instanceof InterruptedException
                    || current instanceof ClosedByInterruptException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static void sleepInterruptibly(Duration delay, Sleeper sleeper) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CancellationException cancellation =
                    new CancellationException(WAIT_INTERRUPTED_MESSAGE);
            cancellation.initCause(e);
            throw cancellation;
        }
    }

    private static void checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            CancellationException cancellation =
                    new CancellationException(WAIT_INTERRUPTED_MESSAGE);
            cancellation.initCause(new InterruptedException(WAIT_INTERRUPTED_MESSAGE));
            throw cancellation;
        }
    }

    private static Duration elapsedSince(long startedAtNanos, LongSupplier nanoClock) {
        long elapsedNanos = nanoClock.getAsLong() - startedAtNanos;
        return elapsedNanos <= 0L ? Duration.ZERO : Duration.ofNanos(elapsedNanos);
    }
}
