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

package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.channels.ClosedByInterruptException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises cancellation through the packaged executor on both JDK implementations. */
@Timeout(15)
class AsyncCancellationTest {
    @BeforeAll
    static void usesPackagedExecutorForCurrentJdk() {
        String location =
                ContinuationActionExecutor.class
                        .getResource("ContinuationActionExecutor.class")
                        .toString();
        assertThat(location).startsWith("jar:");
        boolean continuations = Runtime.version().feature() >= 21;
        assertThat(ContinuationActionExecutor.isContinuationSupported()).isEqualTo(continuations);
        assertThat(location.contains("!/META-INF/versions/21/")).isEqualTo(continuations);
    }

    static Stream<Arguments> cancellations() {
        return Stream.of(
                Arguments.of(new InterruptedException(), true),
                Arguments.of(new ClosedByInterruptException(), true),
                Arguments.of(new RuntimeException(new InterruptedException()), true),
                Arguments.of(new RuntimeException(new ClosedByInterruptException()), true),
                Arguments.of(new CancellationException(), false),
                Arguments.of(new RuntimeException(new CancellationException()), false));
    }

    @ParameterizedTest
    @MethodSource("cancellations")
    void singleCallPropagatesCancellation(Exception failure, boolean interrupts) throws Exception {
        verifyCancellation(failure, interrupts, false);
    }

    @ParameterizedTest
    @MethodSource("cancellations")
    void batchPropagatesCancellation(Exception failure, boolean interrupts) throws Exception {
        verifyCancellation(failure, interrupts, true);
    }

    private static void verifyCancellation(Exception failure, boolean interrupts, boolean batch)
            throws Exception {
        ContinuationActionExecutor executor = new ContinuationActionExecutor(2);
        ContinuationContext continuation = new ContinuationContext();
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "cancellation-test",
                        executor);
        context.setContinuationContext(continuation);
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        AsyncFuture<String> failed =
                context.executeAsync(
                        () -> {
                            throw failure;
                        });
        AsyncFuture<?> handle = batch ? context.gather(List.of(failed)) : failed;
        AtomicReference<Exception> raised = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Runnable action =
                () -> {
                    try {
                        handle.await();
                    } catch (Exception error) {
                        raised.set(error);
                        interrupted.set(Thread.interrupted());
                    }
                };
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!executor.executeAction(continuation, action)) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(raised.get()).isSameAs(failure);
            assertThat(interrupted.get()).isEqualTo(interrupts);
            assertThat(context.getCurrentCallIndex()).isZero();
        } finally {
            Thread.interrupted();
            executor.close();
        }
    }
}
