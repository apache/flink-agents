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
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Error propagation through the packaged multi-release JDK 21 executor. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(15)
class AsyncBatchErrorPropagationTest {
    @BeforeAll
    static void usesPackagedContinuationExecutor() {
        assertThat(ContinuationActionExecutor.isContinuationSupported()).isTrue();
        assertThat(
                        ContinuationActionExecutor.class
                                .getResource("ContinuationActionExecutor.class")
                                .toString())
                .startsWith("jar:")
                .contains("!/META-INF/versions/21/");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalErrorEscapesBusinessExceptionHandler(boolean timeout) throws Exception {
        verifyFailure(timeout, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitCompletionExceptionRemainsABusinessFailure(boolean timeout) throws Exception {
        verifyFailure(timeout, false);
    }

    private static void verifyFailure(boolean timeout, boolean fatal) throws Exception {
        ContinuationActionExecutor executor = new ContinuationActionExecutor(2);
        ContinuationContext continuation = new ContinuationContext();
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "batch-error-test",
                        executor);
        context.setContinuationContext(continuation);
        Configuration config = (Configuration) context.getConfig();
        config.set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        config.set(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT_MS, timeout ? 1000L : -1L);
        AssertionError error = new AssertionError("fatal callback");
        CompletionException businessFailure = new CompletionException(error);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean caughtAsException = new AtomicBoolean();
        AtomicReference<List<Outcome<String>>> result = new AtomicReference<>();
        AsyncFuture<String> failed =
                context.executeAsync(
                        () -> {
                            if (fatal) {
                                throw error;
                            }
                            // An explicitly thrown Exception is still a business failure, even with
                            // an Error cause.
                            throw businessFailure;
                        });
        AsyncFuture<String> sibling =
                context.executeAsync(
                        () -> {
                            if (timeout) {
                                release.await();
                            }
                            return "success";
                        });
        AsyncFuture<List<Outcome<String>>> batch = context.gather(List.of(failed, sibling));
        Runnable action =
                () -> {
                    try {
                        result.set(batch.await());
                    } catch (Exception ignored) {
                        caughtAsException.set(true);
                    }
                };
        try {
            if (fatal) {
                assertThatThrownBy(() -> finishAction(executor, continuation, action))
                        .isSameAs(error);
            } else {
                finishAction(executor, continuation, action);
                assertThat(result.get().get(0).getError()).isSameAs(businessFailure);
                if (timeout) {
                    assertThat(result.get().get(1).getError()).isInstanceOf(TimeoutException.class);
                } else {
                    assertThat(result.get().get(1).getValue()).isEqualTo("success");
                }
            }
            assertThat(caughtAsException).isFalse();
        } finally {
            release.countDown();
            executor.close();
        }
    }

    private static void finishAction(
            ContinuationActionExecutor executor, ContinuationContext continuation, Runnable action)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!executor.executeAction(continuation, action)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }
}
