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

package org.apache.flink.agents.plan.utils;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationUtilsTest {
    @Test
    void recognizesDirectAndWrappedSignals() {
        for (Exception interruption :
                List.of(new InterruptedException(), new ClosedByInterruptException())) {
            assertThat(CancellationUtils.isInterruption(interruption)).isTrue();
            assertThat(CancellationUtils.isCancellation(interruption)).isTrue();
            assertThat(CancellationUtils.isInterruption(new RuntimeException(interruption)))
                    .isTrue();
            assertThat(CancellationUtils.isCancellation(new RuntimeException(interruption)))
                    .isTrue();
        }
        CancellationException cancelled = new CancellationException();
        assertThat(CancellationUtils.isCancellation(cancelled)).isTrue();
        assertThat(CancellationUtils.isCancellation(new RuntimeException(cancelled))).isTrue();
        assertThat(CancellationUtils.isInterruption(cancelled)).isFalse();
        assertThat(CancellationUtils.isInterruption(new RuntimeException(cancelled))).isFalse();
    }

    @Test
    void ioTimeoutsMessagesAndSuppressedErrorsDoNotImplyCancellation() {
        RuntimeException suppressed = new RuntimeException();
        suppressed.addSuppressed(new InterruptedException());
        for (Exception failure :
                List.of(
                        new InterruptedIOException("timeout"),
                        new SocketTimeoutException(),
                        new RuntimeException("InterruptedException"),
                        suppressed)) {
            assertThat(CancellationUtils.isCancellation(failure)).isFalse();
            assertThat(CancellationUtils.isInterruption(failure)).isFalse();
            assertThat(CancellationUtils.isCancellation(new RuntimeException(failure))).isFalse();
        }
        assertThat(CancellationUtils.isCancellation(null)).isFalse();
    }

    @Test
    void classificationDoesNotReadOrMutateThreadInterruptStatus() {
        try {
            Thread.interrupted();
            assertThat(CancellationUtils.isInterruption(new InterruptedException())).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            Thread.currentThread().interrupt();
            assertThat(CancellationUtils.isCancellation(new IllegalStateException())).isFalse();
            assertThat(CancellationUtils.isCancellation(null)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void causeCyclesTerminate() {
        RuntimeException first = new RuntimeException();
        RuntimeException second = new RuntimeException(first);
        first.initCause(second);
        assertThat(CancellationUtils.isCancellation(first)).isFalse();
        assertThat(CancellationUtils.isInterruption(first)).isFalse();
    }
}
