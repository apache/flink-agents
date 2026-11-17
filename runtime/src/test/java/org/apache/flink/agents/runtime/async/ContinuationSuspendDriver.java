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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ContinuationActionExecutor#suspend}. Loaded by an isolated class loader so the JDK
 * 21 multi-release classes are used even when surefire's directory classpath would otherwise select
 * the JDK 11 fallback.
 */
public final class ContinuationSuspendDriver {

    private ContinuationSuspendDriver() {}

    public static void run() throws Exception {
        if (!ContinuationActionExecutor.isContinuationSupported()) {
            throw new IllegalStateException(
                    "ContinuationSuspendDriver was loaded without the JDK 21 executor");
        }

        ContinuationActionExecutor executor = new ContinuationActionExecutor(1);
        ContinuationContext context = new ContinuationContext();
        AtomicBoolean finished = new AtomicBoolean();
        try {
            long startNanos = System.nanoTime();
            boolean done =
                    executor.executeAction(
                            context,
                            () -> {
                                try {
                                    executor.suspend(context, 2_000L);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                finished.set(true);
                            });
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            assertFalse(done);
            assertFalse(finished.get());
            assertTrue(elapsedMs < 500L, "suspend held the caller for " + elapsedMs + " ms");

            boolean stillWaiting = executor.executeAction(context, () -> {});
            assertFalse(stillWaiting);
            assertFalse(finished.get());

            long deadlineNanos = System.nanoTime() + 5_000_000_000L;
            while (!done && System.nanoTime() < deadlineNanos) {
                Thread.sleep(20L);
                done = executor.executeAction(context, () -> {});
            }
            assertTrue(finished.get());
            assertTrue(done);
        } finally {
            executor.close();
        }
    }
}
