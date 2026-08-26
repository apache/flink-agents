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
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.runtime.eventlog.EventLogWriter;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EventLogComponentExecutionListener}, the per-action-execution adapter that keeps
 * the event log's start/terminal report pairing.
 */
class EventLogComponentExecutionListenerTest {

    @Test
    void startAndTerminalReportsShareOneChildExecution() {
        CapturingEventLogger logger = new CapturingEventLogger();
        ExecutionTraceContext actionContext = actionTraceContext();
        EventLogComponentExecutionListener listener =
                new EventLogComponentExecutionListener(actionContext, sink(logger));

        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                Map.of(),
                ExecutionLifecycleEvents.executionStarted());
        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                Map.of(),
                ExecutionLifecycleEvents.executionFinished());

        assertThat(logger.records).hasSize(2);
        Event start = logger.records.get(0).event;
        Event terminal = logger.records.get(1).event;
        assertThat(start.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE);
        assertThat(terminal.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE);
        assertThat(status(start)).isEqualTo(ExecutionLifecycleEvents.STATUS_STARTED);
        assertThat(status(terminal)).isEqualTo(ExecutionLifecycleEvents.STATUS_SUCCESS);

        ExecutionTraceContext startContext = logger.records.get(0).traceContext;
        ExecutionTraceContext terminalContext = logger.records.get(1).traceContext;
        assertThat(terminalContext.getExecutionId()).isEqualTo(startContext.getExecutionId());
        assertThat(startContext.getParentExecutionId()).isEqualTo(actionContext.getExecutionId());
        assertThat(startContext.getEntityType()).isEqualTo(ExecutionReporter.EntityTypes.LLM);
        assertThat(startContext.getEntityName()).isEqualTo("model-a");
    }

    @Test
    void pairingSurvivesWhenReportsUseSeparateListenerAccesses() {
        CapturingEventLogger logger = new CapturingEventLogger();
        EventLogComponentExecutionListener listener =
                new EventLogComponentExecutionListener(actionTraceContext(), sink(logger));
        Map<String, Object> metadata = Map.of("toolCallId", "call-1");

        // Mirrors a continuation: the start is reported first, the terminal arrives later
        // through the same per-execution listener instance.
        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                metadata,
                ExecutionLifecycleEvents.executionStarted());
        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.TOOL,
                "search",
                metadata,
                ExecutionLifecycleEvents.executionFailed(
                        "builtins.ValueError",
                        "bad response",
                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED));

        assertThat(logger.records).hasSize(2);
        Event failed = logger.records.get(1).event;
        assertThat(failed.getType())
                .isEqualTo(ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE);
        assertThat(failed.getAttr("errorType")).isEqualTo("builtins.ValueError");
        assertThat(failed.getAttr("errorMessage")).isEqualTo("bad response");
        assertThat(failed.getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE))
                .isEqualTo(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
        assertThat(logger.records.get(1).traceContext.getExecutionId())
                .isEqualTo(logger.records.get(0).traceContext.getExecutionId());
        assertThat(logger.records.get(1).traceContext.getEntityMetadata())
                .containsEntry("toolCallId", "call-1");
    }

    @Test
    void terminalReportWithoutStartGetsAFreshExecutionId() {
        CapturingEventLogger logger = new CapturingEventLogger();
        ExecutionTraceContext actionContext = actionTraceContext();
        EventLogComponentExecutionListener listener =
                new EventLogComponentExecutionListener(actionContext, sink(logger));

        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.PARSER,
                "json-parser",
                Map.of(),
                ExecutionLifecycleEvents.executionFinished());

        assertThat(logger.records).hasSize(1);
        ExecutionTraceContext context = logger.records.get(0).traceContext;
        assertThat(context.getExecutionId()).isNotBlank();
        assertThat(context.getParentExecutionId()).isEqualTo(actionContext.getExecutionId());
    }

    @Test
    void repeatedStartReportReplacesTheActiveReport() {
        CapturingEventLogger logger = new CapturingEventLogger();
        EventLogComponentExecutionListener listener =
                new EventLogComponentExecutionListener(actionTraceContext(), sink(logger));

        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                Map.of(),
                ExecutionLifecycleEvents.executionStarted());
        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                Map.of(),
                ExecutionLifecycleEvents.executionStarted());
        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                Map.of(),
                ExecutionLifecycleEvents.executionFinished());

        assertThat(logger.records).hasSize(3);
        // The terminal pairs with the second start; the first start stays unpaired.
        assertThat(logger.records.get(2).traceContext.getExecutionId())
                .isEqualTo(logger.records.get(1).traceContext.getExecutionId())
                .isNotEqualTo(logger.records.get(0).traceContext.getExecutionId());
    }

    @Test
    void disabledTraceSwitchSuppressesExecutionRecords() {
        CapturingEventLogger logger = new CapturingEventLogger();
        EventLogComponentExecutionListener listener =
                new EventLogComponentExecutionListener(
                        actionTraceContext(),
                        ExecutionEventLogger.forEventLogWriter(
                                EventLogWriter.forEventLogger(logger, false)));

        listener.onComponentExecution(
                ExecutionReporter.EntityTypes.LLM,
                "model-a",
                Map.of(),
                ExecutionLifecycleEvents.executionStarted());

        assertThat(logger.records).isEmpty();
    }

    private static ExecutionTraceContext actionTraceContext() {
        return ExecutionTraceContext.forInputRun("business-key", "agent")
                .childExecution("action", "chat_model_action");
    }

    private static ExecutionEventSink sink(EventLogger logger) {
        return ExecutionEventLogger.forEventLogWriter(EventLogWriter.forEventLogger(logger));
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
