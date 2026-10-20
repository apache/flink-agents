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
package org.apache.flink.agents.runtime.async;

import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.operator.parallel.ActionTaskLifecycleException;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionContextLifecycle;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.apache.flink.agents.runtime.operator.parallel.UncapturableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Tests the JDK<21 mailbox-lock handoff contract. */
@Timeout(15)
class ContinuationActionExecutorTest {

    @BeforeEach
    void requireSynchronousFallback() {
        // This suite pins the JDK<21 synchronous-fallback handoff. On JDK 21+ the multi-release
        // build places the real continuation executor on the test classpath, where executeAsync
        // needs a continuation scope, so the fallback contract under test does not apply there.
        assumeFalse(ContinuationActionExecutor.isContinuationSupported());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspendsAndResumesUnderLockAroundSupplier(boolean batch) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        Queue<String> calls = new ConcurrentLinkedQueue<>();
        ParallelExecutionContextLifecycle lifecycle = recordingLifecycle(lock, calls);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch allowSupplierToFinish = new CountDownLatch(1);
        CountDownLatch supplierFinished = new CountDownLatch(1);
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<String> workerResult =
                    workerExecutor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                try {
                                    return execute(
                                            executor,
                                            context,
                                            batch,
                                            () -> {
                                                assertThat(lock.isHeldByCurrentThread()).isFalse();
                                                calls.add("supplier");
                                                supplierStarted.countDown();
                                                await(allowSupplierToFinish);
                                                supplierFinished.countDown();
                                                return "result";
                                            });
                                } finally {
                                    if (lock.isHeldByCurrentThread()) {
                                        lock.release();
                                    }
                                }
                            });

            assertThat(supplierStarted.await(5, TimeUnit.SECONDS)).isTrue();
            lock.acquireByMain();
            try {
                allowSupplierToFinish.countDown();
                assertThat(supplierFinished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(calls).containsExactly("suspend", "supplier");
            } finally {
                lock.release();
            }
            assertThat(workerResult.get(5, TimeUnit.SECONDS)).isEqualTo("result");
            assertThat(calls).containsExactly("suspend", "supplier", "resume");
        } finally {
            allowSupplierToFinish.countDown();
            workerExecutor.shutdownNow();
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortsBeforeCallbacksAndSupplierWhenCallerDoesNotOwnLock(boolean batch) {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        AtomicBoolean supplierCalled = new AtomicBoolean();
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        try {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            executor,
                                            new ContinuationContext(),
                                            batch,
                                            () -> {
                                                supplierCalled.set(true);
                                                return "unexpected";
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(supplierCalled).isFalse();
            verifyNoInteractions(lifecycle);
        } finally {
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspendFailureKeepsLockAndSkipsSupplierAndResume(boolean batch) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        AtomicBoolean supplierCalled = new AtomicBoolean();
        AssertionError failure = new AssertionError("suspend failed");
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        doAnswer(
                        invocation -> {
                            lock.checkReentrant();
                            throw failure;
                        })
                .when(lifecycle)
                .suspend(null, null);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        lock.acquireByWorker(0, 0);
        try {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            executor,
                                            context,
                                            batch,
                                            () -> {
                                                supplierCalled.set(true);
                                                return "unexpected";
                                            }))
                    .isExactlyInstanceOf(UncapturableException.class)
                    .satisfies(
                            shell ->
                                    assertThat(shell.getCause())
                                            .isExactlyInstanceOf(ActionTaskLifecycleException.class)
                                            .hasMessage("Could not suspend action task")
                                            .hasCause(failure));
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            assertThat(supplierCalled).isFalse();
            verify(lifecycle).suspend(null, null);
            verifyNoMoreInteractions(lifecycle);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.release();
            }
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumeFailureIsUncapturableAfterSupplierCompletes(boolean batch) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        AtomicBoolean supplierCalled = new AtomicBoolean();
        AssertionError failure = new AssertionError("resume failed");
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        doAnswer(
                        invocation -> {
                            lock.checkReentrant();
                            assertThat(supplierCalled).isTrue();
                            throw failure;
                        })
                .when(lifecycle)
                .resume(null, null);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        lock.acquireByWorker(0, 0);
        try {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            executor,
                                            context,
                                            batch,
                                            () -> {
                                                supplierCalled.set(true);
                                                return "result";
                                            }))
                    .isExactlyInstanceOf(UncapturableException.class)
                    .satisfies(
                            shell ->
                                    assertThat(shell.getCause())
                                            .isExactlyInstanceOf(ActionTaskLifecycleException.class)
                                            .hasMessage("Could not resume action task")
                                            .hasCause(failure))
                    .satisfies(shell -> assertThat(shell.getSuppressed()).isEmpty());
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            verify(lifecycle).suspend(null, null);
            verify(lifecycle).resume(null, null);
            verifyNoMoreInteractions(lifecycle);
        } finally {
            lock.release();
            executor.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, true"})
    void resumeFailureSuppressesEscapingSupplierFailure(boolean batch, boolean supplierThrowsError)
            throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        Throwable supplierFailure =
                supplierThrowsError
                        ? new AssertionError("supplier failed")
                        : new IllegalArgumentException("supplier failed");
        IllegalStateException resumeFailure = new IllegalStateException("resume failed");
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        doAnswer(
                        invocation -> {
                            lock.checkReentrant();
                            throw resumeFailure;
                        })
                .when(lifecycle)
                .resume(null, null);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        lock.acquireByWorker(0, 0);
        try {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            executor,
                                            context,
                                            batch,
                                            () -> {
                                                if (supplierFailure instanceof Error) {
                                                    throw (Error) supplierFailure;
                                                }
                                                throw (RuntimeException) supplierFailure;
                                            }))
                    .isExactlyInstanceOf(UncapturableException.class)
                    .satisfies(
                            shell ->
                                    assertThat(shell.getCause())
                                            .isExactlyInstanceOf(ActionTaskLifecycleException.class)
                                            .hasMessage("Could not resume action task")
                                            .hasCause(resumeFailure))
                    .satisfies(
                            shell ->
                                    assertThat(shell.getSuppressed())
                                            .containsExactly(supplierFailure));
            assertThat(lock.isHeldByCurrentThread()).isTrue();
        } finally {
            lock.release();
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supplierErrorIsNotWrappedWhenResumeSucceeds(boolean batch) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        Queue<String> calls = new ConcurrentLinkedQueue<>();
        AssertionError failure = new AssertionError("supplier failed");
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, recordingLifecycle(lock, calls));
        lock.acquireByWorker(0, 0);
        try {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            executor,
                                            context,
                                            batch,
                                            () -> {
                                                calls.add("supplier");
                                                throw failure;
                                            }))
                    .isSameAs(failure);
            assertThat(calls).containsExactly("suspend", "supplier", "resume");
            assertThat(lock.isHeldByCurrentThread()).isTrue();
        } finally {
            lock.release();
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supplierExceptionKeepsBusinessSemantics(boolean batch) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        Queue<String> calls = new ConcurrentLinkedQueue<>();
        IllegalArgumentException failure = new IllegalArgumentException("supplier failed");
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, recordingLifecycle(lock, calls));
        Supplier<String> supplier =
                () -> {
                    calls.add("supplier");
                    throw failure;
                };
        lock.acquireByWorker(0, 0);
        try {
            if (batch) {
                BatchExecutionResult<String> result =
                        executor.executeAllAsync(
                                context, List.of(supplier::get, () -> "second"), null, 1);
                assertThat(result.getOutcomes().get(0).getError()).isSameAs(failure);
                assertThat(result.getOutcomes().get(1).getValue()).isEqualTo("second");
            } else {
                assertThatThrownBy(() -> executor.executeAsync(context, supplier))
                        .isSameAs(failure);
            }
            assertThat(calls).containsExactly("suspend", "supplier", "resume");
            assertThat(lock.isHeldByCurrentThread()).isTrue();
        } finally {
            lock.release();
            executor.close();
        }
    }

    @Test
    void executeAsyncKeepsResultWhenCallLeavesInterruptFlagSet() throws Throwable {
        assertThat(
                        runInterruptingCall(
                                () -> {
                                    // Code that catches an InterruptedException and re-interrupts.
                                    Thread.currentThread().interrupt();
                                    return "result";
                                }))
                .isEqualTo("result");
    }

    @Test
    void executeAsyncKeepsFailureWhenCallLeavesInterruptFlagSet() {
        IllegalStateException failure = new IllegalStateException("call failed");
        assertThatThrownBy(
                        () ->
                                runInterruptingCall(
                                        () -> {
                                            Thread.currentThread().interrupt();
                                            throw failure;
                                        }))
                .isSameAs(failure);
    }

    /**
     * Runs {@code call} through {@code executeAsync} on a worker that owns the lock, and checks
     * that the worker gets the lock and its context back with the interrupt flag still set.
     */
    private static String runInterruptingCall(Supplier<String> call) throws Throwable {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        AtomicBoolean resumed = new AtomicBoolean();
        AtomicBoolean interruptedAfterCall = new AtomicBoolean();
        ParallelExecutionContextLifecycle lifecycle =
                new ParallelExecutionContextLifecycle() {
                    @Override
                    public void suspend(Object key, ActionTask actionTask) {}

                    @Override
                    public void resume(Object key, ActionTask actionTask) {
                        lock.checkReentrant();
                        resumed.set(true);
                    }
                };
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<String> workerResult =
                    workerExecutor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                try {
                                    return executor.executeAsync(new ContinuationContext(), call);
                                } finally {
                                    interruptedAfterCall.set(Thread.interrupted());
                                    if (lock.isHeldByCurrentThread()) {
                                        lock.release();
                                    }
                                }
                            });
            try {
                return workerResult.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw e.getCause();
            } finally {
                assertThat(resumed).isTrue();
                assertThat(interruptedAfterCall).isTrue();
            }
        } finally {
            workerExecutor.shutdownNow();
            executor.close();
        }
    }

    /**
     * An interrupt arriving while the worker waits to re-acquire -- the coordinator's shutdown
     * interrupt, rather than a flag the call left set -- still abandons the handoff: the worker
     * ends up without the lock and the context is never resumed.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedReacquisitionDoesNotResume(boolean supplierFails) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ContinuationContext context = new ContinuationContext();
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        CountDownLatch inSupplier = new CountDownLatch(1);
        CountDownLatch mainHoldsLock = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<String> workerResult =
                    workerExecutor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                workerThread.set(Thread.currentThread());
                                try {
                                    return executor.executeAsync(
                                            context,
                                            () -> {
                                                inSupplier.countDown();
                                                await(mainHoldsLock);
                                                if (supplierFails) {
                                                    throw new IllegalArgumentException(
                                                            "supplier failed");
                                                }
                                                return "result";
                                            });
                                } finally {
                                    if (lock.isHeldByCurrentThread()) {
                                        lock.release();
                                    }
                                }
                            });
            assertThat(inSupplier.await(5, TimeUnit.SECONDS)).isTrue();

            // Hold the lock here so the worker's re-acquire has to queue behind it, then interrupt
            // that wait. An interrupt landing before the worker queues is consumed by the
            // flag-clearing the handoff does, so it is repeated until the wait is abandoned.
            lock.acquireByMain();
            try {
                mainHoldsLock.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!workerResult.isDone()) {
                    workerThread.get().interrupt();
                    assertThat(System.nanoTime())
                            .as("worker never abandoned the interrupted re-acquire")
                            .isLessThan(deadline);
                    Thread.sleep(10L);
                }
                assertThatThrownBy(() -> workerResult.get(5, TimeUnit.SECONDS))
                        .isExactlyInstanceOf(ExecutionException.class)
                        .hasCauseExactlyInstanceOf(InterruptedException.class);
            } finally {
                lock.release();
            }

            verify(lifecycle).suspend(null, null);
            verify(lifecycle, never()).resume(null, null);
        } finally {
            mainHoldsLock.countDown();
            workerExecutor.shutdownNow();
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executionWithoutLockDoesNotCallLifecycle(boolean batch) throws Exception {
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, null, lifecycle);
        try {
            assertThat(execute(executor, new ContinuationContext(), batch, () -> "result"))
                    .isEqualTo("result");
            verifyNoInteractions(lifecycle);
        } finally {
            executor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyBatchDoesNotCallLifecycleOrChangeLockOwnership(boolean ownsLock) throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ParallelExecutionContextLifecycle lifecycle = mock(ParallelExecutionContextLifecycle.class);
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, lifecycle);
        if (ownsLock) {
            lock.acquireByWorker(0, 0);
        }
        try {
            assertThat(
                            executor.executeAllAsync(new ContinuationContext(), List.of(), null, 1)
                                    .getOutcomes())
                    .isEmpty();
            assertThat(lock.isHeldByCurrentThread()).isEqualTo(ownsLock);
            verifyNoInteractions(lifecycle);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.release();
            }
            executor.close();
        }
    }

    private static ParallelExecutionContextLifecycle recordingLifecycle(
            ParallelExecutionLock lock, Queue<String> calls) {
        return new ParallelExecutionContextLifecycle() {
            @Override
            public void suspend(Object key, ActionTask actionTask) {
                lock.checkReentrant();
                assertThat(key).isNull();
                assertThat(actionTask).isNull();
                calls.add("suspend");
            }

            @Override
            public void resume(Object key, ActionTask actionTask) {
                lock.checkReentrant();
                assertThat(key).isNull();
                assertThat(actionTask).isNull();
                calls.add("resume");
            }
        };
    }

    private static String execute(
            ContinuationActionExecutor executor,
            ContinuationContext context,
            boolean batch,
            Supplier<String> supplier)
            throws Exception {
        if (batch) {
            BatchExecutionResult<String> result =
                    executor.executeAllAsync(context, List.of(supplier::get), null, 1);
            return result.getOutcomes().get(0).getValue();
        }
        return executor.executeAsync(context, supplier);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test supplier", e);
        }
    }
}
