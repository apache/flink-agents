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

package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the ordinary async API through the real runtime scheduler on both JDK paths. */
class JavaRunnerContextImplAsyncExecutionTest {
    @Test
    void singleCallWaitUsesRuntimeScheduler() throws Exception {
        ContinuationActionExecutor executor = new ContinuationActionExecutor(2);
        ContinuationContext continuation = new ContinuationContext();
        JavaRunnerContextImpl context = createContext(executor, continuation);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        boolean continuations = ContinuationActionExecutor.isContinuationSupported();
        try {
            AsyncFuture<String> future =
                    context.executeAsync(
                            () -> {
                                worker.set(Thread.currentThread());
                                started.countDown();
                                if (continuations) {
                                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                                }
                                return "done";
                            });
            assertThat(started.getCount()).isEqualTo(1);
            Runnable action =
                    () -> {
                        try {
                            result.set(future.await());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
            boolean finished = executor.executeAction(continuation, action);
            if (continuations) {
                assertThat(finished).isFalse();
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(result.get()).isNull();
                assertThat(worker.get()).isNotSameAs(Thread.currentThread());
                release.countDown();
                finishAction(executor, continuation, action);
            } else {
                assertThat(finished).isTrue();
                assertThat(worker.get()).isSameAs(Thread.currentThread());
            }
            assertThat(result.get()).isEqualTo("done");
            assertThat(context.getCurrentCallIndex()).isZero();
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void gatherRunsConcurrentlyOnAllSupportedJdks() throws Exception {
        ContinuationActionExecutor executor = new ContinuationActionExecutor(2);
        ContinuationContext continuation = new ContinuationContext();
        JavaRunnerContextImpl context = createContext(executor, continuation);
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicReference<List<Outcome<String>>> result = new AtomicReference<>();
        try {
            AsyncFuture<String> first =
                    context.executeAsync(
                            () -> {
                                bothStarted.countDown();
                                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                                return "first";
                            });
            AsyncFuture<String> second =
                    context.executeAsync(
                            () -> {
                                bothStarted.countDown();
                                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                                return "second";
                            });
            AsyncFuture<List<Outcome<String>>> batch = context.gather(List.of(first, second));
            assertThat(bothStarted.getCount()).isEqualTo(2);
            Runnable action =
                    () -> {
                        try {
                            result.set(batch.await());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
            boolean finished = executor.executeAction(continuation, action);
            if (!finished) {
                finishAction(executor, continuation, action);
            }
            assertThat(result.get())
                    .extracting(Outcome::getValue)
                    .containsExactly("first", "second");
            assertThat(context.getCurrentCallIndex()).isZero();
        } finally {
            executor.close();
        }
    }

    @Test
    void fallbackOrdinaryCallReleasesMailboxLockAndRestoresContext() throws Exception {
        if (ContinuationActionExecutor.isContinuationSupported()) {
            // The continuation path is exercised above; it does not own the fallback lock.
            return;
        }
        ParallelExecutionLock lock = new ParallelExecutionLock();
        AtomicBoolean restored = new AtomicBoolean();
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(
                        1,
                        () -> {},
                        lock,
                        (key, task) -> {
                            lock.checkReentrant();
                            restored.set(true);
                        });
        JavaRunnerContextImpl context = createContext(executor, new ContinuationContext());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> result =
                    worker.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                try {
                                    return context.executeAsync(
                                                    () -> {
                                                        started.countDown();
                                                        assertThat(
                                                                        release.await(
                                                                                5,
                                                                                TimeUnit.SECONDS))
                                                                .isTrue();
                                                        return "done";
                                                    })
                                            .await();
                                } finally {
                                    lock.release();
                                }
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            lock.acquireByMain();
            try {
                assertThat(restored).isFalse();
            } finally {
                lock.release();
            }
            release.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(restored).isTrue();
        } finally {
            release.countDown();
            worker.shutdownNow();
            executor.close();
        }
    }

    private static void finishAction(
            ContinuationActionExecutor executor,
            ContinuationContext continuation,
            Runnable action) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!executor.executeAction(continuation, action)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.yield();
        }
    }

    private static JavaRunnerContextImpl createContext(
            ContinuationActionExecutor executor, ContinuationContext continuation) {
        FlinkAgentsMetricGroupImpl metrics =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        metrics,
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "async-test",
                        executor);
        context.setContinuationContext(continuation);
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        return context;
    }
}
