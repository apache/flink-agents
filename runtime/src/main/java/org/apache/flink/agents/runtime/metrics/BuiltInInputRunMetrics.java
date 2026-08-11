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
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Tracks input-run outcomes and latency on the operator mailbox thread.
 *
 * <p>A run is successful when it reaches the operator's run-completion boundary and failed when an
 * unhandled exception terminates it. Handled or recovered execution failures do not change the
 * final run outcome. End-to-end latency starts when the input enters the operator and is split into
 * queueing and processing latency at the input-run start boundary.
 *
 * <p>Tracking state is intentionally process-local because Flink metrics reset when the task
 * restarts. Latency and outcome samples for runs already in flight when a task is restored are
 * excluded because their original timestamps are unavailable. Current-count gauges are rebuilt from
 * operator state.
 */
final class BuiltInInputRunMetrics {

    static final String NUM_INPUT_RUNS_SUCCEEDED = "numOfInputRunsSucceeded";
    static final String NUM_INPUT_RUNS_FAILED = "numOfInputRunsFailed";
    static final String INPUT_RUN_LATENCY_MS = "inputRunLatencyMs";
    static final String INPUT_RUN_QUEUE_LATENCY_MS = "inputRunQueueLatencyMs";
    static final String INPUT_RUN_PROCESSING_LATENCY_MS = "inputRunProcessingLatencyMs";
    static final String NUM_PENDING_INPUT_EVENTS = "numOfPendingInputEvents";
    static final String NUM_ACTIVE_INPUT_RUNS = "numOfActiveInputRuns";

    private final Counter succeededCounter;
    private final Counter failedCounter;
    private final Histogram latencyHistogram;
    private final Histogram queueLatencyHistogram;
    private final Histogram processingLatencyHistogram;
    private final CurrentCountGauge pendingInputEvents;
    private final CurrentCountGauge activeInputRuns;
    private final LongSupplier nanoTime;

    private final Map<String, Long> receivedInputNanos = new HashMap<>();
    private final Map<String, RunTiming> activeRunTimings = new HashMap<>();
    private final Set<String> activeInputRunIds = new HashSet<>();
    private long unidentifiedRestoredActiveRuns;

    BuiltInInputRunMetrics(FlinkAgentsMetricGroupImpl metricGroup, LongSupplier nanoTime) {
        this.succeededCounter = metricGroup.getCounter(NUM_INPUT_RUNS_SUCCEEDED);
        this.failedCounter = metricGroup.getCounter(NUM_INPUT_RUNS_FAILED);
        this.latencyHistogram = metricGroup.getHistogram(INPUT_RUN_LATENCY_MS);
        this.queueLatencyHistogram = metricGroup.getHistogram(INPUT_RUN_QUEUE_LATENCY_MS);
        this.processingLatencyHistogram = metricGroup.getHistogram(INPUT_RUN_PROCESSING_LATENCY_MS);
        this.pendingInputEvents = new CurrentCountGauge(metricGroup, NUM_PENDING_INPUT_EVENTS);
        this.activeInputRuns = new CurrentCountGauge(metricGroup, NUM_ACTIVE_INPUT_RUNS);
        this.nanoTime = nanoTime;
    }

    void inputEventReceived(Event inputEvent) {
        receivedInputNanos.putIfAbsent(eventId(inputEvent), nanoTime.getAsLong());
    }

    void inputEventFailed(Event inputEvent) {
        String inputEventId = eventId(inputEvent);
        Long receivedNanos = receivedInputNanos.remove(inputEventId);
        if (receivedNanos != null) {
            recordTerminal(true, receivedNanos);
        }
    }

    void inputRunStarted(Event inputEvent, ExecutionTraceContext traceContext) {
        String inputRunId = traceContext.getInputRunId();
        if (inputRunId == null || !activeInputRunIds.add(inputRunId)) {
            return;
        }

        activeInputRuns.increment();
        long startedNanos = nanoTime.getAsLong();
        String inputEventId = eventId(inputEvent);
        Long receivedNanos = receivedInputNanos.remove(inputEventId);
        if (receivedNanos != null) {
            queueLatencyHistogram.update(elapsedMillis(receivedNanos, startedNanos));
        }
        activeRunTimings.put(inputRunId, new RunTiming(receivedNanos, startedNanos));
    }

    void inputRunCompleted(String inputRunId) {
        finish(inputRunId, false);
    }

    void inputRunFailed(String inputRunId) {
        finish(inputRunId, true);
    }

    void pendingInputEventEnqueued() {
        pendingInputEvents.increment();
    }

    void pendingInputEventDequeued() {
        pendingInputEvents.decrement();
    }

    void restorePendingInputEvents(long count) {
        pendingInputEvents.set(count);
    }

    void restoreActiveInputRuns(long count) {
        unidentifiedRestoredActiveRuns = Math.max(0L, count);
        activeInputRuns.set(unidentifiedRestoredActiveRuns + activeInputRunIds.size());
    }

    void identifyRestoredActiveInputRun(String inputRunId) {
        if (inputRunId != null
                && activeInputRunIds.add(inputRunId)
                && unidentifiedRestoredActiveRuns > 0L) {
            unidentifiedRestoredActiveRuns--;
        }
    }

    private void finish(String inputRunId, boolean failed) {
        boolean activeRunFinished = inputRunId != null && activeInputRunIds.remove(inputRunId);
        if (!activeRunFinished && inputRunId == null && unidentifiedRestoredActiveRuns > 0L) {
            unidentifiedRestoredActiveRuns--;
            activeRunFinished = true;
        }
        if (activeRunFinished) {
            activeInputRuns.decrement();
        }

        RunTiming timing = inputRunId == null ? null : activeRunTimings.remove(inputRunId);
        if (timing == null) {
            return;
        }

        long terminalNanos = nanoTime.getAsLong();
        recordOutcome(failed);
        if (timing.receivedNanos != null) {
            latencyHistogram.update(elapsedMillis(timing.receivedNanos, terminalNanos));
        }
        processingLatencyHistogram.update(elapsedMillis(timing.startedNanos, terminalNanos));
    }

    private void recordTerminal(boolean failed, long startNanos) {
        recordOutcome(failed);
        latencyHistogram.update(elapsedMillis(startNanos, nanoTime.getAsLong()));
    }

    private void recordOutcome(boolean failed) {
        if (failed) {
            failedCounter.inc();
        } else {
            succeededCounter.inc();
        }
    }

    private static String eventId(Event event) {
        return event.getId().toString();
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, endNanos - startNanos));
    }

    private static final class RunTiming {
        @Nullable private final Long receivedNanos;
        private final long startedNanos;

        private RunTiming(@Nullable Long receivedNanos, long startedNanos) {
            this.receivedNanos = receivedNanos;
            this.startedNanos = startedNanos;
        }
    }
}
