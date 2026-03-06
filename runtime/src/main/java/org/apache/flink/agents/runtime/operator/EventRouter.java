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
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerFactory;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.eventlog.FileEventLogger;
import org.apache.flink.agents.runtime.metrics.BuiltInMetrics;
import org.apache.flink.agents.runtime.operator.queue.SegmentedQueue;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.types.Row;

import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.BASE_LOG_DIR;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Handles event wrapping, unwrapping, routing to actions, notification (event logger and
 * listeners), and watermark management via a {@link SegmentedQueue}.
 */
class EventRouter {

    private final AgentPlan agentPlan;
    private final boolean inputIsJava;
    private final EventLogger eventLogger;
    private final List<EventListener> eventListeners;
    private final SegmentedQueue keySegmentQueue;
    private BuiltInMetrics builtInMetrics;
    private PythonActionExecutor pythonActionExecutor;

    EventRouter(AgentPlan agentPlan, boolean inputIsJava) {
        this.agentPlan = agentPlan;
        this.inputIsJava = inputIsJava;
        this.eventLogger = createEventLogger(agentPlan);
        this.eventListeners = new ArrayList<>();
        this.keySegmentQueue = new SegmentedQueue();
    }

    SegmentedQueue getKeySegmentQueue() {
        return keySegmentQueue;
    }

    void setBuiltInMetrics(BuiltInMetrics builtInMetrics) {
        this.builtInMetrics = builtInMetrics;
    }

    void setPythonActionExecutor(PythonActionExecutor pythonActionExecutor) {
        this.pythonActionExecutor = pythonActionExecutor;
    }

    /** Initializes the event logger if it is set. */
    void initEventLogger(StreamingRuntimeContext runtimeContext) throws Exception {
        if (eventLogger == null) {
            return;
        }
        eventLogger.open(new EventLoggerOpenParams(runtimeContext));
    }

    /** Notifies event logger and listeners that an event has been processed. */
    void notifyEventProcessed(Event event) throws Exception {
        EventContext eventContext = new EventContext(event);
        if (eventLogger != null) {
            eventLogger.append(eventContext, event);
            eventLogger.flush();
        }
        for (EventListener listener : eventListeners) {
            listener.onEventProcessed(eventContext, event);
        }
        builtInMetrics.markEventProcessed();
    }

    /** Wraps raw input into an InputEvent (Java or Python). */
    @SuppressWarnings("unchecked")
    <IN> Event wrapToInputEvent(IN input) {
        if (inputIsJava) {
            return new InputEvent(input);
        } else {
            checkState(input instanceof Row && ((Row) input).getArity() == 2);
            return pythonActionExecutor.wrapToInputEvent(((Row) input).getField(1));
        }
    }

    /** Extracts output data from an OutputEvent (Java or Python). */
    @SuppressWarnings("unchecked")
    <OUT> OUT getOutputFromOutputEvent(Event event) {
        checkState(EventUtil.isOutputEvent(event));
        if (event instanceof OutputEvent) {
            return (OUT) ((OutputEvent) event).getOutput();
        } else if (event instanceof PythonEvent) {
            return (OUT)
                    pythonActionExecutor.getOutputFromOutputEvent(((PythonEvent) event).getEvent());
        } else {
            throw new IllegalStateException(
                    "Unsupported event type: " + event.getClass().getName());
        }
    }

    /** Gets actions triggered by a given event from the agent plan. */
    List<Action> getActionsTriggeredBy(Event event) {
        if (event instanceof PythonEvent) {
            return agentPlan.getActionsTriggeredBy(((PythonEvent) event).getEventType());
        } else {
            return agentPlan.getActionsTriggeredBy(event.getClass().getName());
        }
    }

    /** Processes and emits all eligible watermarks from the segmented queue. */
    void processEligibleWatermarks(WatermarkEmitter emitter) throws Exception {
        Watermark mark = keySegmentQueue.popOldestWatermark();
        while (mark != null) {
            emitter.emit(mark);
            mark = keySegmentQueue.popOldestWatermark();
        }
    }

    void close() throws Exception {
        if (eventLogger != null) {
            eventLogger.close();
        }
    }

    private static EventLogger createEventLogger(AgentPlan agentPlan) {
        EventLoggerConfig.Builder loggerConfigBuilder = EventLoggerConfig.builder();
        String baseLogDir = agentPlan.getConfig().get(BASE_LOG_DIR);
        if (baseLogDir != null && !baseLogDir.trim().isEmpty()) {
            loggerConfigBuilder.property(FileEventLogger.BASE_LOG_DIR_PROPERTY_KEY, baseLogDir);
        }
        return EventLoggerFactory.createLogger(loggerConfigBuilder.build());
    }

    /** Callback interface for emitting watermarks through the operator. */
    @FunctionalInterface
    interface WatermarkEmitter {
        void emit(Watermark watermark) throws Exception;
    }
}
