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
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.types.Row;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.BASE_LOG_DIR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.PRETTY_PRINT;
import static org.apache.flink.util.Preconditions.checkState;

class EventRouter<IN, OUT> implements AutoCloseable {

    private final boolean inputIsJava;
    private final EventLogger eventLogger;
    private final List<EventListener> eventListeners;
    private StreamRecord<OUT> reusedStreamRecord;
    private SegmentedQueue keySegmentQueue;
    private BuiltInMetrics builtInMetrics;

    EventRouter(AgentPlan agentPlan, boolean inputIsJava) {
        this.inputIsJava = inputIsJava;
        this.eventLogger = createEventLogger(agentPlan);
        this.eventListeners = new ArrayList<>();
    }

    void open(BuiltInMetrics builtInMetrics) {
        this.reusedStreamRecord = new StreamRecord<>(null);
        this.keySegmentQueue = new SegmentedQueue();
        this.builtInMetrics = builtInMetrics;
    }

    void initEventLogger(StreamingRuntimeContext runtimeContext) throws Exception {
        if (eventLogger == null) {
            return;
        }
        eventLogger.open(new EventLoggerOpenParams(runtimeContext));
    }

    @SuppressWarnings("unchecked")
    Event wrapToInputEvent(IN input, PythonActionExecutor pythonActionExecutor) {
        if (inputIsJava) {
            return new InputEvent(input);
        } else {
            // the input data must originate from Python and be of type Row with two fields — the
            // first representing the key, and the second representing the actual data payload.
            checkState(input instanceof Row && ((Row) input).getArity() == 2);
            return pythonActionExecutor.wrapToInputEvent(((Row) input).getField(1));
        }
    }

    @SuppressWarnings("unchecked")
    OUT getOutputFromOutputEvent(Event event, PythonActionExecutor pythonActionExecutor) {
        checkState(EventUtil.isOutputEvent(event));
        if (event instanceof OutputEvent) {
            return (OUT) ((OutputEvent) event).getOutput();
        } else if (event instanceof PythonEvent) {
            Object outputFromOutputEvent =
                    pythonActionExecutor.getOutputFromOutputEvent(((PythonEvent) event).getEvent());
            return (OUT) outputFromOutputEvent;
        } else {
            throw new IllegalStateException(
                    "Unsupported event type: " + event.getClass().getName());
        }
    }

    List<Action> getActionsTriggeredBy(Event event, AgentPlan agentPlan) {
        if (event instanceof PythonEvent) {
            return agentPlan.getActionsTriggeredBy(((PythonEvent) event).getEventType());
        } else {
            return agentPlan.getActionsTriggeredBy(event.getClass().getName());
        }
    }

    void notifyEventProcessed(Event event) throws Exception {
        EventContext eventContext = new EventContext(event);
        if (eventLogger != null) {
            // If event logging is enabled, we log the event along with its context.
            eventLogger.append(eventContext, event);
            // For now, we flush the event logger after each event to ensure immediate logging.
            // This is a temporary solution to ensure that events are logged immediately.
            // TODO: In the future, we may want to implement a more efficient batching mechanism.
            eventLogger.flush();
        }
        if (eventListeners != null) {
            // Notify all registered event listeners about the event.
            for (EventListener listener : eventListeners) {
                listener.onEventProcessed(eventContext, event);
            }
        }
        builtInMetrics.markEventProcessed();
    }

    void processEligibleWatermarks(WatermarkEmitter watermarkEmitter) throws Exception {
        Watermark mark = keySegmentQueue.popOldestWatermark();
        while (mark != null) {
            watermarkEmitter.emit(mark);
            mark = keySegmentQueue.popOldestWatermark();
        }
    }

    @Nullable
    SegmentedQueue getKeySegmentQueue() {
        return keySegmentQueue;
    }

    @Nullable
    StreamRecord<OUT> getReusedStreamRecord() {
        return reusedStreamRecord;
    }

    @VisibleForTesting
    @Nullable
    EventLogger getEventLogger() {
        return eventLogger;
    }

    private EventLogger createEventLogger(AgentPlan agentPlan) {
        EventLoggerConfig.Builder loggerConfigBuilder = EventLoggerConfig.builder();
        String baseLogDir = agentPlan.getConfig().get(BASE_LOG_DIR);
        if (baseLogDir != null && !baseLogDir.trim().isEmpty()) {
            loggerConfigBuilder.property(FileEventLogger.BASE_LOG_DIR_PROPERTY_KEY, baseLogDir);
        }
        loggerConfigBuilder.property(
                FileEventLogger.PRETTY_PRINT_PROPERTY_KEY, agentPlan.getConfig().get(PRETTY_PRINT));
        return EventLoggerFactory.createLogger(loggerConfigBuilder.build());
    }

    @Override
    public void close() throws Exception {
        if (eventLogger != null) {
            eventLogger.close();
        }
    }

    @FunctionalInterface
    interface WatermarkEmitter {
        void emit(Watermark mark) throws Exception;
    }
}
