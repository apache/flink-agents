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
package org.apache.flink.agents.runtime.python.utils;

import org.apache.flink.agents.runtime.async.AsyncExecutorThreadFactory;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreter;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Defect-oriented concurrency tests for {@link PythonInterpreterManager}. */
class PythonInterpreterManagerTest {

    @Test
    void routesUnmanagedThreadCallsAwayFromTheCallerThread() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicReference<Thread> interpreterCreationThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            interpreterCreationThread.set(Thread.currentThread());
                            return mock(PythonInterpreter.class);
                        },
                        ignored -> {})) {
            executor.submit(
                            () -> {
                                callerThread.set(Thread.currentThread());
                                manager.invoke("callback");
                            })
                    .get(5, TimeUnit.SECONDS);

            assertThat(interpreterCreationThread.get())
                    .as("an unmanaged caller must not create a Pemja thread state on itself")
                    .isNotSameAs(callerThread.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundsInterpretersCreatedForTransientUnmanagedThreads() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        AtomicInteger created = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            created.incrementAndGet();
                            return mock(PythonInterpreter.class);
                        },
                        ignored -> {})) {
            for (int i = 0; i < 20; i++) {
                Thread caller =
                        new Thread(
                                () -> {
                                    try {
                                        manager.invoke("callback");
                                    } catch (Throwable t) {
                                        failure.set(t);
                                    }
                                });
                caller.start();
                caller.join(5000);
                assertThat(caller.isAlive()).isFalse();
            }

            assertThat(failure.get()).isNull();
            assertThat(created.get())
                    .as("transient Python-originated callers must reuse bounded callback workers")
                    .isBetween(1, 2);
        }
    }

    @Test
    void managedJavaAsyncWorkerCreatesUsesAndClosesInterpreterOnItself() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        PythonInterpreter worker = mock(PythonInterpreter.class);
        AtomicReference<Thread> creationThread = new AtomicReference<>();
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();

        doAnswer(
                        invocation -> {
                            invocationThread.set(Thread.currentThread());
                            return null;
                        })
                .when(worker)
                .invoke("worker-call");
        doAnswer(
                        invocation -> {
                            closeThread.set(Thread.currentThread());
                            return null;
                        })
                .when(worker)
                .close();

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            creationThread.set(Thread.currentThread());
                            return worker;
                        },
                        ignored -> {})) {
            AsyncExecutorThreadFactory threadFactory =
                    new AsyncExecutorThreadFactory(manager::releaseCurrentThreadInterpreter);
            ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
            try {
                executor.submit(() -> manager.invoke("worker-call")).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                threadFactory.awaitThreadExit();
            }

            assertThat(invocationThread.get()).isSameAs(creationThread.get());
            verify(worker).close();
            assertThat(closeThread.get()).isSameAs(creationThread.get());
        }
    }

    @Test
    void callbackInterpreterIsClosedByItsOwningWorker() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        PythonInterpreter callback = mock(PythonInterpreter.class);
        AtomicReference<Thread> creationThread = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();

        doAnswer(
                        invocation -> {
                            closeThread.set(Thread.currentThread());
                            return null;
                        })
                .when(callback)
                .close();

        PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            creationThread.set(Thread.currentThread());
                            return callback;
                        },
                        ignored -> {});
        try {
            caller.submit(() -> manager.invoke("callback")).get(5, TimeUnit.SECONDS);
            manager.close();

            verify(callback).close();
            assertThat(closeThread.get()).isSameAs(creationThread.get());
        } finally {
            manager.close();
            caller.shutdownNow();
        }
    }

    @Test
    void rejectsCloseFromANonOwnerThread() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(owner, () -> mock(PythonInterpreter.class))) {
            Future<?> close =
                    caller.submit(
                            () -> {
                                manager.close();
                                return null;
                            });

            assertThatThrownBy(() -> close.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                            "Python interpreter manager must be closed by its owner thread.");
            assertThatCode(() -> manager.invoke("still-open")).doesNotThrowAnyException();
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void reusesOwnerInterpreterOnCreatingThread() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        PythonInterpreter unused = mock(PythonInterpreter.class);

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(owner, () -> unused, ignored -> {})) {
            manager.invoke("first");
            manager.invoke("second");

            verify(owner).invoke("first");
            verify(owner).invoke("second");
            verify(unused, never()).invoke("first");
            verify(unused, never()).invoke("second");
        }
    }

    @Test
    void initializesEveryThreadConfinedInterpreter() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        PythonInterpreter worker = mock(PythonInterpreter.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (PythonInterpreterManager manager = new PythonInterpreterManager(owner, () -> worker)) {
            executor.submit(() -> manager.invoke("worker-call")).get(5, TimeUnit.SECONDS);

            verify(owner).exec(PythonInterpreterManager.PYTHON_IMPORTS);
            verify(worker).exec(PythonInterpreterManager.PYTHON_IMPORTS);
            verify(worker).invoke("worker-call");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bindsDifferentInterpretersToDifferentThreads() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        Queue<PythonInterpreter> created = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            PythonInterpreter interpreter = mock(PythonInterpreter.class);
                            created.add(interpreter);
                            return interpreter;
                        },
                        ignored -> {})) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Future<PythonInterpreter> first =
                    executor.submit(
                            () ->
                                    manager.withInterpreter(
                                            interpreter -> {
                                                ready.countDown();
                                                await(release);
                                                PythonInterpreter nested =
                                                        manager.withInterpreter(value -> value);
                                                assertThat(nested).isSameAs(interpreter);
                                                return interpreter;
                                            }));
            Future<PythonInterpreter> second =
                    executor.submit(
                            () ->
                                    manager.withInterpreter(
                                            interpreter -> {
                                                ready.countDown();
                                                await(release);
                                                PythonInterpreter nested =
                                                        manager.withInterpreter(value -> value);
                                                assertThat(nested).isSameAs(interpreter);
                                                return interpreter;
                                            }));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isNotSameAs(second.get(5, TimeUnit.SECONDS));
            assertThat(created).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void doesNotSerializeCallsMadeByDifferentThreads() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            PythonInterpreter interpreter = mock(PythonInterpreter.class);
                            doAnswer(
                                            invocation -> {
                                                entered.countDown();
                                                await(release);
                                                return null;
                                            })
                                    .when(interpreter)
                                    .invoke("blocking");
                            return interpreter;
                        },
                        ignored -> {})) {
            Future<?> first = executor.submit(() -> manager.invoke("blocking"));
            Future<?> second = executor.submit(() -> manager.invoke("blocking"));

            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("both interpreter calls should overlap")
                    .isTrue();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reentrantCallUsesSameInterpreter() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        AtomicReference<PythonInterpreterManager> managerRef = new AtomicReference<>();
        doAnswer(invocation -> managerRef.get().invoke("inner")).when(owner).invoke("outer");

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(
                        owner,
                        () -> {
                            throw new AssertionError("reentrant call created another interpreter");
                        },
                        ignored -> {})) {
            managerRef.set(manager);

            assertThatCode(() -> manager.invoke("outer")).doesNotThrowAnyException();
            verify(owner).invoke("inner");
        }
    }

    @Test
    void reentrantCallbackCallUsesSameInterpreter() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        PythonInterpreter callback = mock(PythonInterpreter.class);
        AtomicReference<PythonInterpreterManager> managerRef = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        doAnswer(invocation -> managerRef.get().invoke("inner")).when(callback).invoke("outer");

        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(owner, () -> callback, ignored -> {})) {
            managerRef.set(manager);

            caller.submit(() -> manager.invoke("outer")).get(5, TimeUnit.SECONDS);
            verify(callback).invoke("inner");
            verify(owner, never()).invoke("inner");
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void closesEveryInterpreterAndRejectsLaterCalls() throws Exception {
        PythonInterpreter owner = mock(PythonInterpreter.class);
        PythonInterpreter worker = mock(PythonInterpreter.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PythonInterpreterManager manager =
                new PythonInterpreterManager(owner, () -> worker, ignored -> {});

        try {
            executor.submit(() -> manager.invoke("worker-call")).get(5, TimeUnit.SECONDS);
            manager.close();

            verify(owner).close();
            verify(worker).close();
            assertThatThrownBy(() -> manager.invoke("after-close"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already closed");
        } finally {
            manager.close();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
