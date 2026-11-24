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

import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.junit.jupiter.api.Test;

import java.nio.channels.ClosedByInterruptException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Unit tests for default {@link AsyncJobModel#wait} and {@link AsyncJobPolling}. */
class AsyncJobModelTest {
    private static final MediaJob JOB = new MediaJob("job-1", 1L, null);

    @Test
    void defaultWaitPollsUntilSucceededAndValidatesArguments() {
        List<MediaJobState<MediaResult<TextBlock>>> states =
                new ArrayList<>(
                        List.of(
                                new MediaJobState<>(JOB, MediaJobStatus.PENDING, null, null, null),
                                new MediaJobState<>(JOB, MediaJobStatus.RUNNING, null, null, null),
                                new MediaJobState<>(
                                        JOB,
                                        MediaJobStatus.SUCCEEDED,
                                        new MediaResult<>(
                                                List.of(new TextBlock("done")),
                                                null,
                                                null,
                                                null,
                                                null),
                                        null,
                                        null)));
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        AsyncJobModel<String, MediaResult<TextBlock>> model =
                new AsyncJobModel<String, MediaResult<TextBlock>>() {
                    @Override
                    public MediaJob submit(String request) {
                        return JOB;
                    }

                    @Override
                    public MediaJobState<MediaResult<TextBlock>> fetch(MediaJob job) {
                        fetches.incrementAndGet();
                        return states.remove(0);
                    }

                    @Override
                    public void cancel(MediaJob job) {
                        cancels.incrementAndGet();
                    }
                };

        MediaResult<TextBlock> result =
                model.wait(JOB, Duration.ofSeconds(5), Duration.ofMillis(1));
        assertThat(result.getContent()).containsExactly(new TextBlock("done"));
        assertThat(fetches.get()).isEqualTo(3);
        assertThat(cancels.get()).isZero();

        assertThatThrownBy(() -> model.wait(null, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("job");
        assertThatThrownBy(() -> model.wait(JOB, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
        assertThatThrownBy(() -> model.wait(JOB, Duration.ofSeconds(-1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
        assertThatThrownBy(() -> model.wait(JOB, Duration.ofSeconds(1), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pollInterval");
        assertThatThrownBy(() -> model.wait(JOB, Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pollInterval must be positive");
    }

    @Test
    void pollingHandlesTerminalErrorsResultlessSuccessTimeoutAndInterruption() throws Exception {
        for (MediaJobStatus terminal :
                List.of(
                        MediaJobStatus.FAILED,
                        MediaJobStatus.CANCELED,
                        MediaJobStatus.UNKNOWN,
                        MediaJobStatus.SUCCEEDED)) {
            MediaJobState<MediaResult<TextBlock>> state =
                    new MediaJobState<>(JOB, terminal, null, "E", "err");
            Throwable error =
                    catchThrowable(
                            () ->
                                    AsyncJobPolling.wait(
                                            job -> state,
                                            JOB,
                                            Duration.ofSeconds(1),
                                            Duration.ofMillis(10),
                                            new AtomicLong()::get,
                                            d -> {}));
            assertThat(error).isInstanceOf(MediaJobException.class);
            assertThat(error).isNotInstanceOf(MediaJobTimeoutException.class);
            assertThat(((MediaJobException) error).getStatus()).isEqualTo(terminal);
        }

        AtomicLong clock = new AtomicLong();
        AtomicInteger timeoutFetches = new AtomicInteger();
        Throwable timeout =
                catchThrowable(
                        () ->
                                AsyncJobPolling.wait(
                                        job ->
                                                new MediaJobState<>(
                                                        job,
                                                        timeoutFetches.incrementAndGet() == 1
                                                                ? MediaJobStatus.PENDING
                                                                : MediaJobStatus.RUNNING,
                                                        null,
                                                        null,
                                                        null),
                                        JOB,
                                        Duration.ofMillis(25),
                                        Duration.ofMillis(10),
                                        clock::get,
                                        delay -> clock.addAndGet(delay.toNanos())));
        assertThat(timeout).isInstanceOf(MediaJobTimeoutException.class);
        assertThat(((MediaJobTimeoutException) timeout).getStatus())
                .isEqualTo(MediaJobStatus.RUNNING);
        assertThat(((MediaJobTimeoutException) timeout).getTimeout())
                .isEqualTo(Duration.ofMillis(25));
        assertThat(timeout.getMessage())
                .isEqualTo("Media job job-1 timed out after PT0.025S (last status: running)");
        assertThat(timeoutFetches.get()).isGreaterThanOrEqualTo(2);
        assertThat(clock.get()).isEqualTo(Duration.ofMillis(25).toNanos());

        // An in-flight fetch started before the deadline may complete past it and return its
        // result.
        AtomicLong slowClock = new AtomicLong();
        MediaResult<TextBlock> slowResult =
                AsyncJobPolling.wait(
                        job -> {
                            slowClock.addAndGet(Duration.ofMillis(40).toNanos());
                            return new MediaJobState<>(
                                    job,
                                    MediaJobStatus.SUCCEEDED,
                                    new MediaResult<>(
                                            List.of(new TextBlock("late")), null, null, null, null),
                                    null,
                                    null);
                        },
                        JOB,
                        Duration.ofMillis(20),
                        Duration.ofMillis(10),
                        slowClock::get,
                        delay -> slowClock.addAndGet(delay.toNanos()));
        assertThat(slowResult.getContent()).containsExactly(new TextBlock("late"));

        // Interrupt during wait sleep stops promptly without throwing MediaJobException.
        CountDownLatch started = new CountDownLatch(1);
        AsyncJobModel<String, MediaResult<TextBlock>> pending =
                new AsyncJobModel<String, MediaResult<TextBlock>>() {
                    @Override
                    public MediaJob submit(String request) {
                        return JOB;
                    }

                    @Override
                    public MediaJobState<MediaResult<TextBlock>> fetch(MediaJob job) {
                        started.countDown();
                        return new MediaJobState<>(job, MediaJobStatus.PENDING, null, null, null);
                    }

                    @Override
                    public void cancel(MediaJob job) {
                        throw new AssertionError("cancel should not be called on interrupt");
                    }
                };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                pending.wait(JOB, Duration.ofSeconds(5), Duration.ofSeconds(5));
                            } catch (Throwable t) {
                                failure.set(t);
                                interruptRestored.set(Thread.interrupted());
                            }
                        });
        worker.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(2000);
        assertThat(failure.get())
                .isInstanceOf(CancellationException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(interruptRestored.get()).isTrue();

        // Interrupt inside fetch (either via flag or ClosedByInterruptException) converts cleanly.
        try {
            Throwable closedInterrupt =
                    catchThrowable(
                            () ->
                                    AsyncJobPolling.wait(
                                            job -> {
                                                throw new RuntimeException(
                                                        new ClosedByInterruptException());
                                            },
                                            JOB,
                                            Duration.ofSeconds(1),
                                            Duration.ofMillis(10)));
            assertThat(closedInterrupt).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        RuntimeException ordinaryFailure = new RuntimeException("http interrupted");
        try {
            Throwable ordinaryInterrupt =
                    catchThrowable(
                            () ->
                                    AsyncJobPolling.wait(
                                            job -> {
                                                Thread.currentThread().interrupt();
                                                throw ordinaryFailure;
                                            },
                                            JOB,
                                            Duration.ofSeconds(1),
                                            Duration.ofMillis(10)));
            assertThat(ordinaryInterrupt)
                    .isInstanceOf(CancellationException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(ordinaryInterrupt.getSuppressed()).containsExactly(ordinaryFailure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        IllegalStateException nonCancellable = new IllegalStateException("fatal");
        try {
            Throwable preserved =
                    catchThrowable(
                            () ->
                                    AsyncJobPolling.wait(
                                            job -> {
                                                Thread.currentThread().interrupt();
                                                throw nonCancellable;
                                            },
                                            JOB,
                                            Duration.ofSeconds(1),
                                            Duration.ofMillis(10),
                                            thrown -> thrown == nonCancellable));
            assertThat(preserved).isSameAs(nonCancellable);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
