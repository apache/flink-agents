/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.runtime.metrics;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class BuiltInInputRunMetricsTest {

    private final AtomicLong nanoTime = new AtomicLong();

    private FlinkAgentsMetricGroupImpl metricGroup;
    private BuiltInInputRunMetrics metrics;

    @BeforeEach
    void setUp() {
        metricGroup =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        metrics = new BuiltInInputRunMetrics(metricGroup, nanoTime::get);
    }

    @Test
    void completedRunIsSuccessfulAndLatencyIncludesQueueTime() {
        setTimeMillis(100L);
        Event inputEvent = new InputEvent("input");
        ExecutionTraceContext inputRun = ExecutionTraceContext.forInputRun("key", "agent");

        metrics.inputEventReceived(inputEvent);
        setTimeMillis(130L);
        metrics.inputRunStarted(inputEvent, inputRun);
        setTimeMillis(200L);
        metrics.inputRunCompleted(inputRun.getInputRunId());

        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_SUCCEEDED).getCount())
                .isEqualTo(1L);
        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_FAILED).getCount()).isZero();
        assertHistogram(BuiltInInputRunMetrics.INPUT_RUN_LATENCY_MS, 1L, 100L);
        assertHistogram(BuiltInInputRunMetrics.INPUT_RUN_QUEUE_LATENCY_MS, 1L, 30L);
        assertHistogram(BuiltInInputRunMetrics.INPUT_RUN_PROCESSING_LATENCY_MS, 1L, 70L);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isZero();
    }

    @Test
    void inputProcessingFailureBeforeRunStartIsRecorded() {
        setTimeMillis(100L);
        Event inputEvent = new InputEvent("input");

        metrics.inputEventReceived(inputEvent);
        setTimeMillis(125L);
        metrics.inputEventFailed(inputEvent);

        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_FAILED).getCount()).isEqualTo(1L);
        assertHistogram(BuiltInInputRunMetrics.INPUT_RUN_LATENCY_MS, 1L, 25L);
        assertThat(histogram(BuiltInInputRunMetrics.INPUT_RUN_QUEUE_LATENCY_MS).getCount())
                .isZero();
        assertThat(histogram(BuiltInInputRunMetrics.INPUT_RUN_PROCESSING_LATENCY_MS).getCount())
                .isZero();
    }

    @Test
    void restoredRunRebuildsActiveGaugeWithoutRecordingHistoricalSamples() {
        setTimeMillis(300L);
        ExecutionTraceContext inputRun = ExecutionTraceContext.forInputRun("key", "agent");

        metrics.restoreActiveInputRuns(1L);
        metrics.identifyRestoredActiveInputRun(inputRun.getInputRunId());
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isEqualTo(1L);

        setTimeMillis(375L);
        metrics.inputRunCompleted(inputRun.getInputRunId());

        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_SUCCEEDED).getCount()).isZero();
        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_FAILED).getCount()).isZero();
        assertThat(histogram(BuiltInInputRunMetrics.INPUT_RUN_LATENCY_MS).getCount()).isZero();
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isZero();
    }

    @Test
    void restoredPendingInputRecordsLocallyObservedOutcomeAndProcessingLatency() {
        Event inputEvent = new InputEvent("restored-pending");
        ExecutionTraceContext inputRun = ExecutionTraceContext.forInputRun("key", "agent");

        metrics.restorePendingInputEvents(1L);
        metrics.pendingInputEventDequeued();
        setTimeMillis(300L);
        metrics.inputRunStarted(inputEvent, inputRun);
        setTimeMillis(375L);
        metrics.inputRunCompleted(inputRun.getInputRunId());

        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_SUCCEEDED).getCount())
                .isEqualTo(1L);
        assertThat(histogram(BuiltInInputRunMetrics.INPUT_RUN_LATENCY_MS).getCount()).isZero();
        assertThat(histogram(BuiltInInputRunMetrics.INPUT_RUN_QUEUE_LATENCY_MS).getCount())
                .isZero();
        assertHistogram(BuiltInInputRunMetrics.INPUT_RUN_PROCESSING_LATENCY_MS, 1L, 75L);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_PENDING_INPUT_EVENTS)).isZero();
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isZero();
    }

    @Test
    void duplicateTerminalForIdentifiedRestoredRunDoesNotConsumeAnonymousRun() {
        ExecutionTraceContext identifiedRun = ExecutionTraceContext.forInputRun("key-1", "agent");

        metrics.restoreActiveInputRuns(2L);
        metrics.identifyRestoredActiveInputRun(identifiedRun.getInputRunId());
        metrics.inputRunCompleted(identifiedRun.getInputRunId());
        metrics.inputRunCompleted(identifiedRun.getInputRunId());

        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isEqualTo(1L);

        metrics.inputRunCompleted(null);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isZero();
    }

    @Test
    void terminalFailureIsAttributedToMatchingRunForSameKey() {
        setTimeMillis(100L);
        Event firstInput = new InputEvent("first");
        ExecutionTraceContext firstRun = ExecutionTraceContext.forInputRun("key", "agent");
        metrics.inputEventReceived(firstInput);
        metrics.inputRunStarted(firstInput, firstRun);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isEqualTo(1L);

        setTimeMillis(110L);
        Event secondInput = new InputEvent("second");
        ExecutionTraceContext secondRun = ExecutionTraceContext.forInputRun("key", "agent");
        metrics.inputEventReceived(secondInput);
        metrics.inputRunStarted(secondInput, secondRun);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isEqualTo(2L);

        setTimeMillis(150L);
        metrics.inputRunCompleted(firstRun.getInputRunId());
        setTimeMillis(180L);
        metrics.inputRunFailed(secondRun.getInputRunId());

        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_SUCCEEDED).getCount())
                .isEqualTo(1L);
        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_FAILED).getCount()).isEqualTo(1L);
        assertThat(histogram(BuiltInInputRunMetrics.INPUT_RUN_LATENCY_MS).getCount()).isEqualTo(2L);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isZero();
    }

    @Test
    void pendingInputGaugeTracksQueueAndRestore() {
        assertThat(gauge(BuiltInInputRunMetrics.NUM_PENDING_INPUT_EVENTS)).isZero();

        metrics.pendingInputEventEnqueued();
        metrics.pendingInputEventEnqueued();
        assertThat(gauge(BuiltInInputRunMetrics.NUM_PENDING_INPUT_EVENTS)).isEqualTo(2L);

        metrics.pendingInputEventDequeued();
        assertThat(gauge(BuiltInInputRunMetrics.NUM_PENDING_INPUT_EVENTS)).isEqualTo(1L);

        metrics.restorePendingInputEvents(3L);
        assertThat(gauge(BuiltInInputRunMetrics.NUM_PENDING_INPUT_EVENTS)).isEqualTo(3L);
    }

    @Test
    void duplicateTerminalNotificationDoesNotUnderflowActiveGauge() {
        Event inputEvent = new InputEvent("input");
        ExecutionTraceContext inputRun = ExecutionTraceContext.forInputRun("key", "agent");
        metrics.inputEventReceived(inputEvent);
        metrics.inputRunStarted(inputEvent, inputRun);

        metrics.inputRunCompleted(inputRun.getInputRunId());
        metrics.inputRunCompleted(inputRun.getInputRunId());

        assertThat(gauge(BuiltInInputRunMetrics.NUM_ACTIVE_INPUT_RUNS)).isZero();
        assertThat(counter(BuiltInInputRunMetrics.NUM_INPUT_RUNS_SUCCEEDED).getCount())
                .isEqualTo(1L);
    }

    private Counter counter(String name) {
        return metricGroup.getCounter(name);
    }

    private Histogram histogram(String name) {
        return metricGroup.getHistogram(name);
    }

    private long gauge(String name) {
        return (Long) metricGroup.getGauge(name).getValue();
    }

    private void setTimeMillis(long millis) {
        nanoTime.set(MILLISECONDS.toNanos(millis));
    }

    private void assertHistogram(String name, long count, long max) {
        Histogram histogram = histogram(name);
        assertThat(histogram.getCount()).isEqualTo(count);
        assertThat(histogram.getStatistics().getMax()).isEqualTo(max);
    }
}
