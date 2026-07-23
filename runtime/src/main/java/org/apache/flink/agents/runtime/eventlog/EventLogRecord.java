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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;

import javax.annotation.Nullable;

import java.util.Objects;

/** A normalized Event Log record ready to be persisted. */
@JsonSerialize(using = EventLogRecordJsonSerializer.class)
@JsonDeserialize(using = EventLogRecordJsonDeserializer.class)
public class EventLogRecord {
    private final EventContext eventContext;
    @Nullable private final ExecutionTraceContext traceContext;
    private final Event event;

    public EventLogRecord(
            EventContext eventContext, @Nullable ExecutionTraceContext traceContext, Event event) {
        this.eventContext = Objects.requireNonNull(eventContext, "eventContext");
        this.event = Objects.requireNonNull(event, "event");
        if (!event.getType().equals(eventContext.getEventType())) {
            throw new IllegalArgumentException(
                    "EventContext event type must match Event type. context="
                            + eventContext.getEventType()
                            + ", event="
                            + event.getType());
        }
        this.traceContext = traceContext;
    }

    public EventContext getEventContext() {
        return eventContext;
    }

    @Nullable
    public ExecutionTraceContext getExecutionTraceContext() {
        return traceContext;
    }

    public Event getEvent() {
        return event;
    }
}
