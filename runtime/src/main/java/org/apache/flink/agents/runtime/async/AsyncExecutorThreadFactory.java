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

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * {@link ThreadFactory} for the Flink Agents Java async executor, producing descriptive,
 * collision-resistant thread names of the form {@code flink-agents-java-async-pool-N-thread-M}.
 *
 * <p>Default executor names such as {@code pool-N-thread-M} make Flink Agents async workers hard to
 * attribute in TaskManager thread dumps and profiler output, where many unrelated pools coexist.
 *
 * <p>Thread creation is delegated to {@link Executors#defaultThreadFactory()}, which normalizes
 * daemon status and priority regardless of the calling thread (a directly constructed {@code new
 * Thread(...)} would inherit both from it). This factory only prepends the {@code
 * flink-agents-java-async-} prefix to the delegate's pool- and worker-numbered name.
 */
public final class AsyncExecutorThreadFactory implements ThreadFactory {

    private static final String NAME_PREFIX = "flink-agents-java-async-";
    private static final ThreadLocal<Boolean> ASYNC_EXECUTOR_THREAD = new ThreadLocal<>();

    private final ThreadFactory delegate = Executors.defaultThreadFactory();
    private final Runnable threadCleanup;
    private final Queue<Thread> createdThreads = new ConcurrentLinkedQueue<>();

    public AsyncExecutorThreadFactory() {
        this(() -> {});
    }

    public AsyncExecutorThreadFactory(Runnable threadCleanup) {
        this.threadCleanup = Objects.requireNonNull(threadCleanup);
    }

    /** Returns whether the calling thread is owned by the Flink Agents Java async executor. */
    public static boolean isAsyncExecutorThread() {
        return Boolean.TRUE.equals(ASYNC_EXECUTOR_THREAD.get());
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread =
                delegate.newThread(
                        () -> {
                            ASYNC_EXECUTOR_THREAD.set(true);
                            try {
                                runnable.run();
                            } finally {
                                try {
                                    threadCleanup.run();
                                } finally {
                                    ASYNC_EXECUTOR_THREAD.remove();
                                }
                            }
                        });
        thread.setName(NAME_PREFIX + thread.getName());
        createdThreads.add(thread);
        return thread;
    }

    /** Waits until every worker created by this factory has run its exit cleanup. */
    public void awaitThreadExit() {
        boolean interrupted = false;
        try {
            for (Thread thread : createdThreads) {
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
