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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.eventlog.EventLogWriter;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.operator.queue.SegmentedQueue;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.types.Row;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.InstantiationUtil;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.EVENT_LISTENERS;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Handles event-side concerns for {@link ActionExecutionOperator}: input/output transformation
 * between Java/Python representations, action lookup against the {@link AgentPlan}, Event Log
 * writing, event-listener notification, and watermark draining via the per-key segment queue.
 *
 * <p>Owned state:
 *
 * <ul>
 *   <li>The shared {@link EventLogWriter} owned by the operator.
 *   <li>The list of registered {@link EventListener}s.
 *   <li>A reused {@link StreamRecord} used to emit outputs without per-record allocation.
 *   <li>The {@link SegmentedQueue} that orders watermarks behind in-flight keys so a watermark is
 *       only emitted once all keys ahead of it have finished.
 *   <li>The late-bound {@link BuiltInMetrics} provided in {@link #open(BuiltInMetrics)}.
 * </ul>
 *
 * <p>Lifecycle: instantiated in the operator constructor (which decides {@link #inputIsJava}).
 * {@link #open(BuiltInMetrics)} runs from the operator's {@code open()} once metrics are available.
 *
 * <p>Design constraint: package-private; no manager-to-manager held references.
 *
 * @param <IN> input record type
 * @param <OUT> output record type
 */
class EventRouter<IN, OUT> {

    private final boolean inputIsJava;
    private final EventLogWriter eventLogWriter;
    private final List<EventListener> eventListeners;
    private final AgentPlan agentPlan;
    private StreamRecord<OUT> reusedStreamRecord;
    private SegmentedQueue keySegmentQueue;
    private BuiltInMetrics builtInMetrics;

    EventRouter(AgentPlan agentPlan, boolean inputIsJava) {
        this(agentPlan, inputIsJava, EventLogWriter.create(agentPlan));
    }

    @VisibleForTesting
    EventRouter(AgentPlan agentPlan, boolean inputIsJava, EventLogger eventLogger) {
        this(agentPlan, inputIsJava, EventLogWriter.forEventLogger(eventLogger));
    }

    EventRouter(AgentPlan agentPlan, boolean inputIsJava, EventLogWriter eventLogWriter) {
        this.agentPlan = agentPlan;
        this.inputIsJava = inputIsJava;
        this.eventLogWriter = eventLogWriter;
        this.eventListeners = new ArrayList<>();
    }

    /**
     * Initializes mutable runtime state that depends on metrics being available.
     *
     * <p>Allocates the reused stream record and the segmented watermark queue, and stores the
     * supplied {@link BuiltInMetrics} for use in {@link #notifyEventProcessed(Event,
     * ExecutionTraceContext)}. Called from the operator's {@code open()} once metric groups are
     * constructed.
     *
     * @param builtInMetrics the operator's built-in metrics handle.
     */
    void open(BuiltInMetrics builtInMetrics) {
        this.reusedStreamRecord = new StreamRecord<>(null);
        this.keySegmentQueue = new SegmentedQueue();
        this.builtInMetrics = builtInMetrics;
    }

    /**
     * Initializes the {@link EventListener}s configured for this agent.
     *
     * @throws RuntimeException if any listener class fails to instantiate.
     */
    void initEventListeners(StreamingRuntimeContext runtimeContext) {
        final List<String> eventListenerClassList = agentPlan.getConfig().get(EVENT_LISTENERS);
        if (eventListenerClassList == null || eventListenerClassList.isEmpty()) {
            return;
        }

        final ClassLoader userCodeClassLoader = runtimeContext.getUserCodeClassLoader();
        final List<EventListener> eventListeners = new ArrayList<>();
        for (String listenerClassName : eventListenerClassList) {
            try {
                eventListeners.add(
                        InstantiationUtil.instantiate(
                                listenerClassName, EventListener.class, userCodeClassLoader));
            } catch (FlinkException e) {
                throw new RuntimeException(
                        "Failed to instantiate EventListener: " + listenerClassName, e);
            }
        }
        this.eventListeners.addAll(eventListeners);
    }

    /**
     * Wraps an incoming record into an {@link Event} suitable for action dispatch.
     *
     * <p>Java pipelines wrap the raw input directly into a Java {@link InputEvent}. Python
     * pipelines expect a two-field {@link Row} where the first field is the key and the second is
     * the actual payload; the payload is converted to a Python event via the supplied {@link
     * PythonActionExecutor}.
     *
     * @param input the raw input record.
     * @param pythonActionExecutor the Python action executor (used only when input originates from
     *     Python).
     * @return the wrapped input event.
     */
    @SuppressWarnings("unchecked")
    Event wrapToInputEvent(IN input, PythonActionExecutor pythonActionExecutor) throws Exception {
        if (inputIsJava) {
            return new InputEvent(input);
        } else {
            // the input data must originate from Python and be of type Row with two fields — the
            // first representing the key, and the second representing the actual data payload.
            checkState(input instanceof Row && ((Row) input).getArity() == 2);
            return pythonActionExecutor.wrapToInputEvent(((Row) input).getField(1));
        }
    }

    /**
     * Extracts the downstream output payload from an output {@link Event}.
     *
     * <p>Dispatch is by pipeline wire format, not action language:
     *
     * <ul>
     *   <li>Java pipelines ({@code inputIsJava}) emit the raw payload directly.
     *   <li>Python pipelines re-encode through {@link
     *       PythonActionExecutor#getOutputFromOutputEvent(String)} so the downstream Python sink
     *       receives cloudpickle bytes.
     * </ul>
     *
     * @param event the output event (must satisfy {@link EventUtil#isOutputEvent(Event)}).
     * @param pythonActionExecutor used only on Python pipelines.
     * @return the typed output payload.
     */
    @SuppressWarnings("unchecked")
    OUT getOutputFromOutputEvent(Event event, PythonActionExecutor pythonActionExecutor) {
        checkState(EventUtil.isOutputEvent(event));
        OutputEvent typedEvent =
                (event instanceof OutputEvent) ? (OutputEvent) event : OutputEvent.fromEvent(event);
        if (inputIsJava) {
            return (OUT) typedEvent.getOutput();
        }
        try {
            String eventJson = new ObjectMapper().writeValueAsString(typedEvent);
            return (OUT) pythonActionExecutor.getOutputFromOutputEvent(eventJson);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to extract output from event: " + event.getType(), e);
        }
    }

    List<Action> getActionsTriggeredBy(Event event, AgentPlan agentPlan) {
        return agentPlan.getActionsTriggeredBy(event.getType());
    }

    /**
     * Notifies the configured event sinks (logger, listeners, metrics) that an event was processed.
     *
     * <p>If event logging is enabled, appends and immediately flushes the event best-effort. Then
     * notifies every registered {@link EventListener}. Finally increments the {@code
     * eventProcessed} built-in metric. The event logger is flushed per call as a temporary measure
     * pending a batched flush mechanism.
     *
     * @param event the event that was just processed.
     */
    void notifyEventProcessed(Event event) throws Exception {
        notifyEventProcessed(event, null);
    }

    void notifyEventProcessed(Event event, ExecutionTraceContext traceContext) throws Exception {
        EventContext eventContext = new EventContext(event);
        eventLogWriter.appendBusinessEventAndFlush(eventContext, event, traceContext);
        if (eventListeners != null) {
            // Notify all registered event listeners about the event.
            for (EventListener listener : eventListeners) {
                listener.onEventProcessed(eventContext, event);
            }
        }
        builtInMetrics.markEventProcessed();
    }

    /**
     * Drains all watermarks from the segmented queue that are now eligible to be emitted.
     *
     * <p>A watermark becomes eligible once every key in the segment ahead of it has finished
     * processing. This method pops watermarks in order and forwards each to the supplied {@link
     * WatermarkEmitter}.
     *
     * @param watermarkEmitter callback that emits a watermark downstream.
     */
    void processEligibleWatermarks(WatermarkEmitter watermarkEmitter) throws Exception {
        Watermark mark = keySegmentQueue.popOldestWatermark();
        while (mark != null) {
            watermarkEmitter.emit(mark);
            mark = keySegmentQueue.popOldestWatermark();
        }
    }

    SegmentedQueue getKeySegmentQueue() {
        return keySegmentQueue;
    }

    StreamRecord<OUT> getReusedStreamRecord() {
        return reusedStreamRecord;
    }

    @VisibleForTesting
    @Nullable
    EventLogger getEventLogger() {
        return eventLogWriter.getEventLogger();
    }

    @VisibleForTesting
    void addEventListener(EventListener listener) {
        eventListeners.add(listener);
    }

    @FunctionalInterface
    interface WatermarkEmitter {
        void emit(Watermark mark) throws Exception;
    }
}
