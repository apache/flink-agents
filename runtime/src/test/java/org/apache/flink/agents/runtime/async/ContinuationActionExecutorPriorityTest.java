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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests the order in which the JDK 21 continuation executor runs queued async work. */
class ContinuationActionExecutorPriorityTest {

    @BeforeEach
    void requireContinuations() {
        assumeTrue(ContinuationActionExecutor.isContinuationSupported());
    }

    @Test
    void queuedWorkRunsInRecordThenTaskOrder() throws Exception {
        ContinuationActionExecutor executor = new ContinuationActionExecutor(1);
        try {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            List<String> runOrder = new CopyOnWriteArrayList<>();
            List<ContinuationContext> contexts = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();

            // Occupies the only async thread so the work submitted below queues up.
            ContinuationContext blocker = context(0, 0);
            contexts.add(blocker);
            actions.add(
                    () ->
                            executeAsync(
                                    executor,
                                    blocker,
                                    () -> {
                                        blockerStarted.countDown();
                                        await(releaseBlocker);
                                        return "blocker";
                                    }));
            assertThat(executor.executeAction(blocker, actions.get(0))).isFalse();
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Submitted in the reverse of the expected order. The batch goes through
            // executeAllAsync, the rest through executeAsync.
            ContinuationContext laterRecord = context(2, 0);
            contexts.add(laterRecord);
            actions.add(
                    () -> {
                        try {
                            executor.executeAllAsync(
                                    laterRecord,
                                    List.of(() -> runOrder.add("record 2")),
                                    Duration.ZERO,
                                    1);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            ContinuationContext laterTask = context(1, 1);
            contexts.add(laterTask);
            actions.add(
                    () -> executeAsync(executor, laterTask, () -> runOrder.add("record 1 task 1")));
            ContinuationContext earliestTask = context(1, 0);
            contexts.add(earliestTask);
            actions.add(
                    () ->
                            executeAsync(
                                    executor, earliestTask, () -> runOrder.add("record 1 task 0")));
            for (int i = 1; i < actions.size(); i++) {
                assertThat(executor.executeAction(contexts.get(i), actions.get(i))).isFalse();
            }

            releaseBlocker.countDown();
            for (int i = 0; i < actions.size(); i++) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!executor.executeAction(contexts.get(i), actions.get(i))) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(1);
                }
            }

            assertThat(runOrder).containsExactly("record 1 task 0", "record 1 task 1", "record 2");
        } finally {
            executor.close();
        }
    }

    private static ContinuationContext context(long recordIndex, long taskIndex) {
        ContinuationContext context = new ContinuationContext();
        context.setPriority(recordIndex, taskIndex);
        return context;
    }

    private static <T> void executeAsync(
            ContinuationActionExecutor executor,
            ContinuationContext context,
            Supplier<T> supplier) {
        try {
            executor.executeAsync(context, supplier);
        } catch (Exception e) {
            throw new RuntimeException(e);
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
