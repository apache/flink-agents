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
import org.apache.flink.util.ExceptionUtils;
import pemja.core.PythonInterpreter;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Owns the Pemja interpreters used by one operator subtask.
 *
 * <p>A {@link PythonInterpreter} is never shared by concurrently executing threads. The owner
 * mailbox thread and Flink Agents' managed Java async workers bind an interpreter to themselves.
 * Calls from every other thread are routed to a bounded set of Java callback workers, each of which
 * owns its interpreter. This distinction is required for threads created by CPython (for example
 * Mem0 or Python's async executor): creating a Pemja interpreter there would attach a second {@code
 * PyThreadState} to the same native thread.
 *
 * <p>Pemja's {@code MULTI_THREAD} interpreters share the same CPython main interpreter, so Python
 * resource objects can still be initialized once and passed as opaque handles to calls made by any
 * of these thread-confined interpreters. Calls from one external thread always use the same
 * callback worker, keeping multi-step conversions involving opaque Python handles on one
 * interpreter. Each interpreter has its own globals, however, so the bridge modules must be
 * imported for every newly created interpreter.
 */
public final class PythonInterpreterManager implements AutoCloseable {

    private static final int DEFAULT_CALLBACK_WORKERS = 2;
    private static final AtomicInteger CALLBACK_POOL_ID = new AtomicInteger();

    static final String PYTHON_IMPORTS =
            "from flink_agents.plan import function\n"
                    + "from flink_agents.runtime import flink_runner_context\n"
                    + "from flink_agents.runtime import python_java_utils";

    private final Supplier<PythonInterpreter> interpreterFactory;
    private final Consumer<PythonInterpreter> interpreterInitializer;
    private final Thread ownerThread;
    private final Map<Thread, PythonInterpreter> interpreters = new ConcurrentHashMap<>();
    private final ThreadLocal<PythonInterpreter> threadInterpreter = new ThreadLocal<>();
    private final ThreadLocal<Integer> callbackLane;
    private final ExecutorService[] callbackExecutors;
    private final Queue<Thread> callbackThreads = new ConcurrentLinkedQueue<>();
    private final Queue<Throwable> workerCloseFailures = new ConcurrentLinkedQueue<>();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    private volatile boolean closed;

    public PythonInterpreterManager(
            PythonInterpreter ownerInterpreter, Supplier<PythonInterpreter> interpreterFactory) {
        this(
                ownerInterpreter,
                interpreterFactory,
                PythonInterpreterManager::initializeInterpreter,
                DEFAULT_CALLBACK_WORKERS);
    }

    public PythonInterpreterManager(
            PythonInterpreter ownerInterpreter,
            Supplier<PythonInterpreter> interpreterFactory,
            int callbackWorkerCount) {
        this(
                ownerInterpreter,
                interpreterFactory,
                PythonInterpreterManager::initializeInterpreter,
                callbackWorkerCount);
    }

    PythonInterpreterManager(
            PythonInterpreter ownerInterpreter,
            Supplier<PythonInterpreter> interpreterFactory,
            Consumer<PythonInterpreter> interpreterInitializer) {
        this(
                ownerInterpreter,
                interpreterFactory,
                interpreterInitializer,
                DEFAULT_CALLBACK_WORKERS);
    }

    PythonInterpreterManager(
            PythonInterpreter ownerInterpreter,
            Supplier<PythonInterpreter> interpreterFactory,
            Consumer<PythonInterpreter> interpreterInitializer,
            int callbackWorkerCount) {
        if (callbackWorkerCount <= 0) {
            throw new IllegalArgumentException("Callback worker count must be greater than zero.");
        }
        this.interpreterFactory = Objects.requireNonNull(interpreterFactory);
        this.interpreterInitializer = Objects.requireNonNull(interpreterInitializer);
        this.ownerThread = Thread.currentThread();

        PythonInterpreter initializedOwner = initialize(Objects.requireNonNull(ownerInterpreter));
        interpreters.put(ownerThread, initializedOwner);
        threadInterpreter.set(initializedOwner);
        callbackExecutors = createCallbackExecutors(callbackWorkerCount);
        AtomicInteger nextCallbackLane = new AtomicInteger();
        callbackLane =
                ThreadLocal.withInitial(
                        () ->
                                Math.floorMod(
                                        nextCallbackLane.getAndIncrement(),
                                        callbackExecutors.length));
    }

    /**
     * Executes an operation on a thread-confined interpreter.
     *
     * <p>The owner and managed Java async threads execute inline. An unmanaged caller is commonly a
     * CPython-created thread, so it is assigned to a stable callback lane instead of receiving a
     * second Python thread state itself.
     */
    public <T> T withInterpreter(Function<PythonInterpreter, T> operation) {
        lifecycleLock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("Python interpreter manager is already closed.");
            }
            Function<PythonInterpreter, T> checkedOperation = Objects.requireNonNull(operation);
            PythonInterpreter current = threadInterpreter.get();
            if (current != null) {
                return checkedOperation.apply(current);
            }
            if (AsyncExecutorThreadFactory.isAsyncExecutorThread()) {
                return checkedOperation.apply(createAndBindInterpreter());
            }
            return executeOnCallbackWorker(checkedOperation);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public void exec(String code) {
        withInterpreter(
                interpreter -> {
                    interpreter.exec(code);
                    return null;
                });
    }

    public Object invoke(String name, Object... args) {
        return withInterpreter(interpreter -> interpreter.invoke(name, args));
    }

    public Object get(String name) {
        return withInterpreter(interpreter -> interpreter.get(name));
    }

    public void set(String name, Object value) {
        withInterpreter(
                interpreter -> {
                    interpreter.set(name, value);
                    return null;
                });
    }

    private PythonInterpreter createAndBindInterpreter() {
        PythonInterpreter interpreter = threadInterpreter.get();
        if (interpreter != null) {
            return interpreter;
        }

        PythonInterpreter created = initialize(interpreterFactory.get());
        interpreters.put(Thread.currentThread(), created);
        threadInterpreter.set(created);
        return created;
    }

    private <T> T executeOnCallbackWorker(Function<PythonInterpreter, T> operation) {
        int lane = callbackLane.get();
        Future<T> result =
                callbackExecutors[lane].submit(() -> operation.apply(createAndBindInterpreter()));
        return awaitResult(result);
    }

    private static <T> T awaitResult(Future<T> result) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return result.get();
                } catch (InterruptedException e) {
                    // A bridge invocation cannot be abandoned while close() may reclaim its native
                    // thread state. Finish the accepted operation, then restore interruption.
                    interrupted = true;
                } catch (ExecutionException e) {
                    ExceptionUtils.rethrow(e.getCause());
                    throw new AssertionError("Unreachable after rethrowing callback failure");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private PythonInterpreter initialize(PythonInterpreter interpreter) {
        Objects.requireNonNull(interpreter);
        try {
            interpreterInitializer.accept(interpreter);
            return interpreter;
        } catch (Throwable initializationFailure) {
            try {
                interpreter.close();
            } catch (Throwable closeFailure) {
                initializationFailure.addSuppressed(closeFailure);
            }
            throw initializationFailure;
        }
    }

    private static void initializeInterpreter(PythonInterpreter interpreter) {
        interpreter.exec(PYTHON_IMPORTS);
    }

    /** Releases the interpreter owned by the current managed Java worker. */
    public void releaseCurrentThreadInterpreter() {
        if (!AsyncExecutorThreadFactory.isAsyncExecutorThread()) {
            return;
        }
        lifecycleLock.writeLock().lock();
        try {
            closeCurrentInterpreterAndRecordFailure();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void closeCurrentInterpreterAndRecordFailure() {
        Thread thread = Thread.currentThread();
        PythonInterpreter interpreter = threadInterpreter.get();
        threadInterpreter.remove();
        PythonInterpreter registered = interpreters.remove(thread);
        if (registered == null) {
            return;
        }
        if (registered != interpreter) {
            workerCloseFailures.add(
                    new IllegalStateException(
                            "The interpreter registered for worker "
                                    + thread.getName()
                                    + " did not match its thread-local interpreter."));
        }
        try {
            registered.close();
        } catch (Throwable closeFailure) {
            workerCloseFailures.add(closeFailure);
        }
    }

    private ExecutorService[] createCallbackExecutors(int callbackWorkerCount) {
        int poolId = CALLBACK_POOL_ID.incrementAndGet();
        ThreadFactory delegate = Executors.defaultThreadFactory();
        ExecutorService[] executors = new ExecutorService[callbackWorkerCount];
        for (int i = 0; i < callbackWorkerCount; i++) {
            int workerId = i + 1;
            executors[i] =
                    Executors.newSingleThreadExecutor(
                            runnable -> {
                                Thread thread =
                                        delegate.newThread(
                                                () -> {
                                                    try {
                                                        runnable.run();
                                                    } finally {
                                                        closeCurrentInterpreterAndRecordFailure();
                                                    }
                                                });
                                thread.setName(
                                        "flink-agents-python-callback-pool-"
                                                + poolId
                                                + "-thread-"
                                                + workerId);
                                callbackThreads.add(thread);
                                return thread;
                            });
        }
        return executors;
    }

    @Override
    public synchronized void close() throws Exception {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Python interpreter manager must be closed by its owner thread.");
        }

        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
        } finally {
            lifecycleLock.writeLock().unlock();
        }

        for (ExecutorService callbackExecutor : callbackExecutors) {
            callbackExecutor.shutdown();
        }
        for (ExecutorService callbackExecutor : callbackExecutors) {
            awaitTermination(callbackExecutor);
        }
        awaitThreadExit(callbackThreads);

        closeCurrentInterpreterAndRecordFailure();
        callbackLane.remove();

        // Managed worker executors are closed before this manager. A dead thread cannot retain a
        // live GILState TLS slot, so this is only a defensive fallback for a worker whose exit hook
        // was unable to run. Never delete a live thread's Python state from the owner thread.
        for (Map.Entry<Thread, PythonInterpreter> entry :
                new ArrayList<>(interpreters.entrySet())) {
            Thread thread = entry.getKey();
            if (thread.isAlive()) {
                workerCloseFailures.add(
                        new IllegalStateException(
                                "Python interpreter worker is still alive during manager close: "
                                        + thread.getName()));
                continue;
            }
            if (interpreters.remove(thread) == entry.getValue()) {
                try {
                    entry.getValue().close();
                } catch (Throwable closeFailure) {
                    workerCloseFailures.add(closeFailure);
                }
            }
        }

        Throwable firstFailure = null;
        Throwable closeFailure;
        while ((closeFailure = workerCloseFailures.poll()) != null) {
            firstFailure = ExceptionUtils.firstOrSuppressed(closeFailure, firstFailure);
        }
        if (firstFailure != null) {
            ExceptionUtils.rethrowException(firstFailure);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitThreadExit(Iterable<Thread> threads) {
        boolean interrupted = false;
        try {
            for (Thread thread : threads) {
                while (thread.isAlive()) {
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
