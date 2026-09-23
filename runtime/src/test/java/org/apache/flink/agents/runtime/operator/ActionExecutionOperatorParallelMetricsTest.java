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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Component-level tests for metrics bookkeeping on the JDK&lt;21 parallel execution path. */
class ActionExecutionOperatorParallelMetricsTest {

    private static final int NUM_ASYNC_THREADS = 2;
    private static final int NUM_ACTIONS = 2;

    @Test
    @Timeout(20)
    void completedParallelActionsClearPendingAndSchedulingMetrics() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        MetricsAgent.reset();

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                newHarness(MetricsAgent.getAgentPlan())) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator = operator(testHarness);

            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(MetricsAgent.ACTIONS_STARTED.await(5, TimeUnit.SECONDS)).isTrue();
            MetricsAgent.ALLOW_ACTIONS.countDown();
            operator.waitInFlightEventsFinished();

            FlinkAgentsMetricGroupImpl metricGroup = metricGroup(operator);
            long pendingTasks = 0L;
            long schedulingSamples = 0L;
            for (int i = 1; i <= NUM_ACTIONS; i++) {
                FlinkAgentsMetricGroupImpl actionMetrics =
                        metricGroup.getSubGroup("action", "metricAction" + i);
                pendingTasks += gauge(actionMetrics, "numOfPendingActionTasks");
                schedulingSamples +=
                        actionMetrics.getHistogram("actionSchedulingLatencyMs").getCount();
            }

            assertThat(List.of(pendingTasks, schedulingSamples))
                    .as("dequeue must clear pending tasks and consume their enqueue timestamps")
                    .containsExactly(0L, (long) NUM_ACTIONS);
        } finally {
            MetricsAgent.ALLOW_ACTIONS.countDown();
        }
    }

    @Test
    @Timeout(20)
    void completedQueuedInputClearsPendingInputMetric() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        MetricsAgent.reset();

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                newHarness(MetricsAgent.getAgentPlan())) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator = operator(testHarness);

            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(MetricsAgent.ACTIONS_STARTED.await(5, TimeUnit.SECONDS)).isTrue();
            testHarness.processElement(new StreamRecord<>(0L));

            FlinkAgentsMetricGroupImpl metricGroup = metricGroup(operator);
            assertThat(gauge(metricGroup, "numOfPendingInputEvents")).isEqualTo(1L);

            MetricsAgent.ALLOW_ACTIONS.countDown();
            operator.waitInFlightEventsFinished();

            assertThat(gauge(metricGroup, "numOfPendingInputEvents"))
                    .as("consuming a queued input must decrement its pending gauge")
                    .isZero();
        } finally {
            MetricsAgent.ALLOW_ACTIONS.countDown();
        }
    }

    @Test
    @Timeout(20)
    void internalNoopTaskDoesNotParticipateInActionMetrics() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();

        AgentConfiguration config = parallelConfig();
        AgentPlan noActionPlan =
                new AgentPlan(new LinkedHashMap<>(), new LinkedHashMap<>(), config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                newHarness(noActionPlan)) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator = operator(testHarness);

            testHarness.processElement(new StreamRecord<>(0L));
            operator.waitInFlightEventsFinished();

            assertThat(testHarness.getRecordOutput()).isEmpty();
            assertThat(gauge(metricGroup(operator), "numOfPendingInputEvents")).isZero();
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> newHarness(
            AgentPlan agentPlan) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(agentPlan, true),
                (KeySelector<Long, Long>) value -> value,
                TypeInformation.of(Long.class));
    }

    @SuppressWarnings("unchecked")
    private static ActionExecutionOperator<Long, Object> operator(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness) {
        return (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
    }

    private static FlinkAgentsMetricGroupImpl metricGroup(
            ActionExecutionOperator<Long, Object> operator) throws Exception {
        Field field = ActionExecutionOperator.class.getDeclaredField("metricGroup");
        field.setAccessible(true);
        return (FlinkAgentsMetricGroupImpl) field.get(operator);
    }

    private static long gauge(FlinkAgentsMetricGroupImpl metricGroup, String name) {
        return ((Number) metricGroup.getGauge(name).getValue()).longValue();
    }

    private static AgentConfiguration parallelConfig() {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);
        return config;
    }

    public static class MetricsAgent {

        private static volatile CountDownLatch ACTIONS_STARTED = new CountDownLatch(0);
        private static volatile CountDownLatch ALLOW_ACTIONS = new CountDownLatch(0);

        private static void reset() {
            ACTIONS_STARTED = new CountDownLatch(NUM_ACTIONS);
            ALLOW_ACTIONS = new CountDownLatch(1);
        }

        private static void runAction(Event event, RunnerContext context, int actionNumber)
                throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            context.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "metric-action-" + actionNumber + "-" + input;
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() throws Exception {
                                    ACTIONS_STARTED.countDown();
                                    if (!ALLOW_ACTIONS.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "Timed out waiting to release metrics actions");
                                    }
                                    return "done";
                                }
                            })
                    .await();
        }

        public static void metricAction1(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 1);
        }

        public static void metricAction2(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 2);
        }

        private static AgentPlan getAgentPlan() throws Exception {
            Map<String, Action> actions = new LinkedHashMap<>();
            for (int i = 1; i <= NUM_ACTIONS; i++) {
                String actionName = "metricAction" + i;
                actions.put(
                        actionName,
                        new Action(
                                actionName,
                                new JavaFunction(
                                        MetricsAgent.class,
                                        actionName,
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE)));
            }
            return new AgentPlan(actions, new LinkedHashMap<>(), parallelConfig());
        }
    }
}
