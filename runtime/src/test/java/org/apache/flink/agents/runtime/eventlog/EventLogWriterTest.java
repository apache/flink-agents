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
package org.apache.flink.agents.runtime.eventlog;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Contract tests for {@link EventLogWriter}. */
class EventLogWriterTest {

    @Test
    void appendAndFlushIgnoresAppendFailure() throws Exception {
        EventLogger mockLogger = mock(EventLogger.class);
        EventLogWriter writer = EventLogWriter.forEventLogger(mockLogger);
        InputEvent inputEvent = new InputEvent(1L);
        EventContext eventContext = new EventContext(inputEvent);
        doThrow(new RuntimeException("append failed"))
                .when(mockLogger)
                .append(any(EventContext.class), eq(inputEvent), isNull());

        assertThatCode(() -> writer.appendBusinessEventAndFlush(eventContext, inputEvent, null))
                .doesNotThrowAnyException();
        verify(mockLogger, never()).flush();
    }

    @Test
    void appendAndFlushIgnoresFlushFailure() throws Exception {
        EventLogger mockLogger = mock(EventLogger.class);
        EventLogWriter writer = EventLogWriter.forEventLogger(mockLogger);
        InputEvent inputEvent = new InputEvent(1L);
        EventContext eventContext = new EventContext(inputEvent);
        doThrow(new RuntimeException("flush failed")).when(mockLogger).flush();

        assertThatCode(() -> writer.appendBusinessEventAndFlush(eventContext, inputEvent, null))
                .doesNotThrowAnyException();
        verify(mockLogger).append(any(EventContext.class), eq(inputEvent), isNull());
        verify(mockLogger).flush();
    }

    @Test
    void traceDisabledKeepsBusinessEventWithoutTraceContext() throws Exception {
        EventLogger mockLogger = mock(EventLogger.class);
        EventLogWriter writer = EventLogWriter.forEventLogger(mockLogger, false);
        InputEvent inputEvent = new InputEvent(1L);
        EventContext eventContext = new EventContext(inputEvent);
        ExecutionTraceContext traceContext =
                ExecutionTraceContext.forInputRun("business-key", "agent");

        writer.appendBusinessEventAndFlush(eventContext, inputEvent, traceContext);

        verify(mockLogger).append(eq(eventContext), eq(inputEvent), isNull());
        verify(mockLogger).flush();
    }

    @Test
    void traceDisabledSkipsExecutionEvent() {
        EventLogger mockLogger = mock(EventLogger.class);
        EventLogWriter writer = EventLogWriter.forEventLogger(mockLogger, false);

        writer.appendExecutionEventAndFlush(
                ExecutionLifecycleEvents.executionStarted(),
                ExecutionTraceContext.forInputRun("business-key", "agent")
                        .childExecution("action", "action1"));

        verifyNoInteractions(mockLogger);
    }

    @Test
    void traceEnabledWritesExecutionEventWithTraceContext() throws Exception {
        EventLogger mockLogger = mock(EventLogger.class);
        EventLogWriter writer = EventLogWriter.forEventLogger(mockLogger, true);
        ExecutionTraceContext traceContext =
                ExecutionTraceContext.forInputRun("business-key", "agent")
                        .childExecution("action", "action1");
        Event executionEvent = ExecutionLifecycleEvents.executionStarted();

        writer.appendExecutionEventAndFlush(executionEvent, traceContext);

        verify(mockLogger).append(any(EventContext.class), eq(executionEvent), eq(traceContext));
        verify(mockLogger).flush();
    }
}
