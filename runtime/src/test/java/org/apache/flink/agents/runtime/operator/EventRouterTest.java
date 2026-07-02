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
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.operator.queue.SegmentedQueue;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contract tests for {@link EventRouter}. */
class EventRouterTest {

    @Test
    void wrapToInputEventReturnsJavaInputEventForJavaInput() throws Exception {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);

        Event event = router.wrapToInputEvent(42L, /* pythonActionExecutor */ null);

        assertThat(event).isInstanceOf(InputEvent.class);
        assertThat(((InputEvent) event).getInput()).isEqualTo(42L);
    }

    @Test
    void getActionsTriggeredByReturnsActionsForJavaEventClass() throws Exception {
        Action action = TestActions.noopAction();
        Map<String, Action> actions = Map.of(action.getName(), action);
        Map<String, List<Action>> byEvent = Map.of(InputEvent.EVENT_TYPE, List.of(action));
        AgentPlan plan = new AgentPlan(actions, byEvent);

        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);

        List<Action> triggered = router.getActionsTriggeredBy(new InputEvent(0L));

        assertThat(triggered).containsExactly(action);
    }

    /**
     * Verifies {@code notifyEventProcessed} dispatches {@code onEventProcessed} to every registered
     * {@link EventListener} with the same {@link EventContext} and event payload.
     */
    @Test
    void notifyEventProcessedNotifiesAllListeners() throws Exception {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventLogger mockLogger = mock(EventLogger.class);
        EventRouter<Long, Object> router =
                new EventRouter<>(plan, /* inputIsJava */ true, mockLogger);

        EventListener listener1 = mock(EventListener.class);
        EventListener listener2 = mock(EventListener.class);
        router.addEventListener(listener1);
        router.addEventListener(listener2);

        router.open(makeMetrics());

        InputEvent inputEvent = new InputEvent(7L);
        router.notifyEventProcessed(inputEvent);

        verify(listener1).onEventProcessed(any(EventContext.class), eq(inputEvent));
        verify(listener2).onEventProcessed(any(EventContext.class), eq(inputEvent));
    }

    /**
     * Verifies {@code notifyEventProcessed} increments the {@code numOfEventProcessed} metric by
     * delegating to {@link BuiltInMetrics#markEventProcessed()}.
     */
    @Test
    void notifyEventProcessedIncrementsMetric() throws Exception {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventLogger mockLogger = mock(EventLogger.class);
        EventRouter<Long, Object> router =
                new EventRouter<>(plan, /* inputIsJava */ true, mockLogger);

        BuiltInMetrics spyMetrics = spy(makeMetrics());
        router.open(spyMetrics);

        router.notifyEventProcessed(new InputEvent(1L));

        verify(spyMetrics).markEventProcessed();
    }

    /**
     * Verifies {@code notifyEventProcessed} calls {@code append} on the event logger followed by
     * {@code flush}, in that order.
     */
    @Test
    void notifyEventProcessedAppendsAndFlushesLogger() throws Exception {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventLogger mockLogger = mock(EventLogger.class);
        EventRouter<Long, Object> router =
                new EventRouter<>(plan, /* inputIsJava */ true, mockLogger);

        router.open(makeMetrics());

        InputEvent inputEvent = new InputEvent(3L);
        router.notifyEventProcessed(inputEvent);

        InOrder ordered = inOrder(mockLogger);
        ordered.verify(mockLogger).append(any(EventContext.class), eq(inputEvent));
        ordered.verify(mockLogger).flush();
    }

    /**
     * Verifies watermarks are drained in arrival order — even when the keys ahead of them finish
     * out-of-order. The {@link SegmentedQueue} closes a segment when a watermark is added, so the
     * sequence {@code addKey(k1), addKey(k2), addWatermark(WM1), addKey(k3), addWatermark(WM2)}
     * forms two segments: {@code [{k1,k2} | WM1]} then {@code [{k3} | WM2]}. Even if {@code k2}
     * finishes before {@code k1} and {@code k3} finishes before either, the emitted order must be
     * {@code [WM1, WM2]}.
     */
    @Test
    void processEligibleWatermarksDrainsInArrivalOrder() throws Exception {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);
        router.open(makeMetrics());

        SegmentedQueue queue = router.getKeySegmentQueue();
        Watermark wm1 = new Watermark(100L);
        Watermark wm2 = new Watermark(200L);

        queue.addKeyToLastSegment("k1");
        queue.addKeyToLastSegment("k2");
        queue.addWatermark(wm1);
        queue.addKeyToLastSegment("k3");
        queue.addWatermark(wm2);

        // Finish keys out of arrival order.
        queue.removeKey("k2");
        queue.removeKey("k3");
        queue.removeKey("k1");

        List<Watermark> emitted = new ArrayList<>();
        router.processEligibleWatermarks(emitted::add);

        assertThat(emitted).containsExactly(wm1, wm2);
    }

    /**
     * Verifies a watermark stays buffered while its preceding segment still has an in-flight key,
     * and is emitted only after the segment drains.
     */
    @Test
    void processEligibleWatermarksBlocksWhileSegmentHasKeys() throws Exception {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);
        router.open(makeMetrics());

        SegmentedQueue queue = router.getKeySegmentQueue();
        Watermark wm1 = new Watermark(100L);

        queue.addKeyToLastSegment("k1");
        queue.addWatermark(wm1);

        List<Watermark> emitted = new ArrayList<>();
        router.processEligibleWatermarks(emitted::add);
        // k1 is still in flight → wm1 must be held back.
        assertThat(emitted).isEmpty();

        queue.removeKey("k1");
        router.processEligibleWatermarks(emitted::add);
        assertThat(emitted).containsExactly(wm1);
    }

    /** Java pipeline + typed OutputEvent — raw payload, no PythonActionExecutor. */
    @Test
    void getOutputFromOutputEventReturnsRawPayloadForTypedOutputEventOnJavaPipeline() {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);
        PythonActionExecutor mockPython = mock(PythonActionExecutor.class);

        Object output = router.getOutputFromOutputEvent(new OutputEvent(42L), mockPython);

        assertThat(output).isEqualTo(42L);
        verify(mockPython, never()).getOutputFromOutputEvent(any());
    }

    /**
     * Java pipeline + unified Event with {@code _output_event} type (e.g. a Python action body
     * emitted it on a Java pipeline) — reconstruct via {@link OutputEvent#fromEvent(Event)}, never
     * round-trip through {@link PythonActionExecutor}.
     */
    @Test
    void getOutputFromOutputEventReturnsRawPayloadForUnifiedEventOnJavaPipeline() {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ true);
        PythonActionExecutor mockPython = mock(PythonActionExecutor.class);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("output", 84L);
        Event unified = new Event(OutputEvent.EVENT_TYPE, attrs);

        Object output = router.getOutputFromOutputEvent(unified, mockPython);

        assertThat(output).isEqualTo(84L);
        verify(mockPython, never()).getOutputFromOutputEvent(any());
    }

    /** Python pipeline — re-encode through PythonActionExecutor for the cloudpickle bytes. */
    @Test
    void getOutputFromOutputEventRoundsTripsThroughPythonOnPythonPipeline() {
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        EventRouter<Long, Object> router = new EventRouter<>(plan, /* inputIsJava */ false);
        PythonActionExecutor mockPython = mock(PythonActionExecutor.class);
        byte[] pickled = new byte[] {1, 2, 3};
        when(mockPython.getOutputFromOutputEvent(any())).thenReturn(pickled);

        Object output = router.getOutputFromOutputEvent(new OutputEvent(42L), mockPython);

        assertThat(output).isEqualTo(pickled);
        verify(mockPython).getOutputFromOutputEvent(any());
    }

    private static BuiltInMetrics makeMetrics() {
        FlinkAgentsMetricGroupImpl metricGroup =
                mock(FlinkAgentsMetricGroupImpl.class, RETURNS_DEEP_STUBS);
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        return new BuiltInMetrics(metricGroup, plan);
    }
}
