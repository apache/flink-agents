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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link AsyncExecutorThreadFactory} thread naming. */
class AsyncExecutorThreadFactoryTest {

    @Test
    @DisplayName("Threads carry the descriptive flink-agents-java-async name")
    void testThreadNamesCarryDescriptivePrefix() throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(2, new AsyncExecutorThreadFactory());
        try {
            String name = executor.submit(() -> Thread.currentThread().getName()).get();
            assertThat(name).matches("flink-agents-java-async-pool-\\d+-thread-\\d+");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Distinct factories produce distinct pool ids, distinct workers distinct ids")
    void testNamesDistinctAcrossPoolsAndWorkers() {
        AsyncExecutorThreadFactory first = new AsyncExecutorThreadFactory();
        AsyncExecutorThreadFactory second = new AsyncExecutorThreadFactory();

        String firstPoolWorker1 = first.newThread(() -> {}).getName();
        String firstPoolWorker2 = first.newThread(() -> {}).getName();
        String secondPoolWorker1 = second.newThread(() -> {}).getName();

        assertThat(firstPoolWorker1).isNotEqualTo(firstPoolWorker2);
        assertThat(firstPoolWorker1).isNotEqualTo(secondPoolWorker1);
        // Pool segment differs between factories.
        String firstPool = firstPoolWorker1.replaceAll("-thread-\\d+$", "");
        String secondPool = secondPoolWorker1.replaceAll("-thread-\\d+$", "");
        assertThat(firstPool).isNotEqualTo(secondPool);
    }

    @Test
    @DisplayName(
            "Daemon status and priority are normalized like the default factory, not inherited")
    void testDaemonStatusAndPriorityNormalizedNotInherited() throws Exception {
        // Create workers from a daemon, max-priority thread: a plain new Thread(...) would
        // inherit both attributes, while the default-factory delegate normalizes them.
        AtomicReference<Thread> created = new AtomicReference<>();
        Thread creator =
                new Thread(() -> created.set(new AsyncExecutorThreadFactory().newThread(() -> {})));
        creator.setDaemon(true);
        creator.setPriority(Thread.MAX_PRIORITY);
        creator.start();
        creator.join(5000);

        assertThat(created.get()).isNotNull();
        assertThat(created.get().isDaemon()).isFalse();
        assertThat(created.get().getPriority()).isEqualTo(Thread.NORM_PRIORITY);
    }
}
