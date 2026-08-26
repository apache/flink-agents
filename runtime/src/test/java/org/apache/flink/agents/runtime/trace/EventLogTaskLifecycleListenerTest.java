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
package org.apache.flink.agents.runtime.trace;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.runtime.eventlog.EventLogWriter;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.operator.JavaActionTask;
import org.apache.flink.agents.runtime.operator.TestActions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EventLogTaskLifecycleListener}, the event log's view of the action lifecycle.
 * These pin the record sequence and attributes of the pre-listener direct-emission path.
 */
class EventLogTaskLifecycleListenerTest {

    @Test
    void lifecycleCallbacksEmitTheOriginalExecutionLifecycleSequence() {
        CapturingEventLogger logger = new CapturingEventLogger();
        EventLogTaskLifecycleListener listener =
                new EventLogTaskLifecycleListener(
                        ExecutionEventLogger.forEventLogWriter(
                                EventLogWriter.forEventLogger(logger)));
        ActionTask task = new JavaActionTask("k", new InputEvent(1L), TestActions.noopAction(), 1L);

        listener.onActionStarted(task);
        listener.onActionFinished(task);

        assertThat(logger.records).hasSize(2);
        assertThat(logger.records.get(0).event.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE);
        assertThat(logger.records.get(1).event.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE);
        assertThat(logger.records.get(0).traceContext).isEqualTo(task.getTraceContext());
        assertThat(logger.records.get(1).traceContext).isEqualTo(task.getTraceContext());
    }

    @Test
    void reuseEmitsExecutionReusedOnTheTaskTraceContext() {
        CapturingEventLogger logger = new CapturingEventLogger();
        EventLogTaskLifecycleListener listener =
                new EventLogTaskLifecycleListener(
                        ExecutionEventLogger.forEventLogWriter(
                                EventLogWriter.forEventLogger(logger)));
        ActionTask task = new JavaActionTask("k", new InputEvent(1L), TestActions.noopAction(), 1L);

        listener.onActionReused(task);

        assertThat(logger.records).hasSize(1);
        assertThat(logger.records.get(0).event.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_REUSED_EVENT_TYPE);
        assertThat(status(logger.records.get(0).event))
                .isEqualTo(ExecutionLifecycleEvents.STATUS_REUSED);
        assertThat(logger.records.get(0).traceContext).isEqualTo(task.getTraceContext());
    }

    @Test
    void failureCarriesRootCauseDetailsAndTheActionExecutionCategory() {
        CapturingEventLogger logger = new CapturingEventLogger();
        EventLogTaskLifecycleListener listener =
                new EventLogTaskLifecycleListener(
                        ExecutionEventLogger.forEventLogWriter(
                                EventLogWriter.forEventLogger(logger)));
        ActionTask task = new JavaActionTask("k", new InputEvent(1L), TestActions.noopAction(), 1L);

        listener.onActionFailed(
                task, new RuntimeException(new IllegalStateException("action boom")));

        assertThat(logger.records).hasSize(1);
        Event failed = logger.records.get(0).event;
        assertThat(failed.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE);
        assertThat(failed.getAttr("errorType")).isEqualTo(IllegalStateException.class.getName());
        assertThat(failed.getAttr("errorMessage")).isEqualTo("action boom");
        assertThat(failed.getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE))
                .isEqualTo(ExecutionReporter.ProblemCategories.ACTION_EXECUTION_FAILED);
        assertThat(logger.records.get(0).traceContext).isEqualTo(task.getTraceContext());
    }

    private static String status(Event event) {
        return (String) event.getAttr(ExecutionLifecycleEvents.STATUS_ATTRIBUTE);
    }

    /** Records every appended (event, trace context) pair. */
    private static final class CapturingEventLogger implements EventLogger {
        private final List<Appended> records = new ArrayList<>();

        @Override
        public void open(EventLoggerOpenParams params) {}

        @Override
        public void append(EventContext eventContext, Event event) {
            append(eventContext, event, null);
        }

        @Override
        public void append(
                EventContext eventContext,
                Event event,
                @Nullable ExecutionTraceContext traceContext) {
            records.add(new Appended(event, traceContext));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static final class Appended {
        private final Event event;
        private final ExecutionTraceContext traceContext;

        private Appended(Event event, ExecutionTraceContext traceContext) {
            this.event = event;
            this.traceContext = traceContext;
        }
    }
}
