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

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor for Java actions that supports asynchronous execution using JDK 21+ Continuation API.
 *
 * <p>This version uses {@code jdk.internal.vm.Continuation} to implement true async execution.
 */
public class ContinuationActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationActionExecutor.class);

    private static final ContinuationScope SCOPE = new ContinuationScope("FlinkAgentsAction");

    private final ExecutorService asyncExecutor;

    public ContinuationActionExecutor(int numAsyncThreads) {
        LOG.info("Initialize fixed thread pool for async task with {} threads", numAsyncThreads);
        this.asyncExecutor =
                Executors.newFixedThreadPool(numAsyncThreads);
    }

    /**
     * Executes the action inside a Continuation.
     *
     * <p>If the action calls executeAsync and yields, this method checks if the async Future is
     * done. If not done, returns false to indicate the action is not finished. If done, resumes the
     * Continuation.
     *
     * @param context the continuation context for this action
     * @param action the action to execute
     * @return true if the action completed, false if waiting for async execution
     */
    public boolean executeAction(ContinuationContext context, Runnable action) {
        // Check if we have a pending async Future from previous yield
        Future<?> pending = context.getPendingFuture();
        if (pending != null) {
            if (!pending.isDone()) {
                // Async task not done yet, return false to wait
                return false;
            }
            // Async task done, clear the pending future and resume
            LOG.debug("Async task done...");
            context.setPendingFuture(null);
        }

        Continuation currentContinuation = context.getCurrentContinuation();
        if (currentContinuation == null) {
            // First invocation: create new Continuation
            LOG.debug("Create new continuation.");
            currentContinuation = new Continuation(SCOPE, action);
            context.setCurrentContinuation(currentContinuation);
        }

        // Run the continuation. It returns either when the action completes or when it yields
        // inside executeAsync; in the latter case we return false and let the next executeAction
        // call observe pendingFuture completion and resume.
        currentContinuation.run();

        if (currentContinuation.isDone()) {
            // Continuation completed
            context.setCurrentContinuation(null);
            LOG.debug("Current continuation is done.");
            return true;
        } else {
            // Continuation yielded, waiting for async task
            // pendingFuture should have been set by executeAsync
            LOG.debug("Current continuation still running.");
            return false;
        }
    }

    /**
     * Asynchronously executes the provided supplier using Continuation.
     *
     * <p>This method submits the task to a thread pool and yields the Continuation. The next call
     * to executeAction will check if the Future is done and resume accordingly.
     *
     * @param context the continuation context for this action
     * @param supplier the supplier to execute
     * @param <T> the result type
     * @return the result of the supplier
     * @throws Exception if the async execution fails
     */
    @SuppressWarnings("unchecked")
    public <T> T executeAsync(ContinuationContext context, Supplier<T> supplier) throws Exception {
        // Clear previous state
        context.clearAsyncState();

        // Submit task to thread pool and store the Future
        Future<?> future =
                asyncExecutor.submit(
                        () -> {
                            try {
                                T result = supplier.get();
                                context.getAsyncResultRef().set(result);
                            } catch (Throwable t) {
                                context.getAsyncExceptionRef().set(t);
                            }
                        });

        // Store the future reference before yielding (volatile write ensures visibility)
        context.setPendingFuture(future);

        // Yield until the future is done
        while (!future.isDone()) {
            Continuation.yield(SCOPE);
        }

        // Check for exception from the async task
        Throwable exception = context.getAsyncExceptionRef().get();
        if (exception != null) {
            if (exception instanceof Exception) {
                throw (Exception) exception;
            } else if (exception instanceof Error) {
                throw (Error) exception;
            } else {
                throw new RuntimeException(exception);
            }
        }

        return (T) context.getAsyncResultRef().get();
    }

    public void close() {
        asyncExecutor.shutdownNow();
    }

    /**
     * Returns whether continuation-based async execution is supported.
     *
     * @return true (this is the JDK 21+ version)
     */
    public static boolean isContinuationSupported() {
        return true;
    }
}
