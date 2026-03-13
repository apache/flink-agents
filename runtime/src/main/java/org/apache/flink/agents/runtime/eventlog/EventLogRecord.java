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
import org.apache.flink.agents.api.logger.EventLogLevel;

import java.util.Objects;

/**
 * Represents a record in the event log, containing the event context and the event itself.
 *
 * <p>This class is used to encapsulate the details of an event as it is logged, allowing for
 * structured logging and retrieval of event information.
 *
 * <p>The class uses custom JSON serialization/deserialization to handle polymorphic Event types by
 * leveraging the eventType information stored in the EventContext.
 */
@JsonSerialize(using = EventLogRecordJsonSerializer.class)
@JsonDeserialize(using = EventLogRecordJsonDeserializer.class)
public class EventLogRecord {
    private final EventContext context;
    private final Event event;
    private final EventLogLevel logLevel;
    private final int maxFieldLength;

    /** Creates a record with the default log level ({@link EventLogLevel#STANDARD}). */
    public EventLogRecord(EventContext context, Event event) {
        this(context, event, EventLogLevel.STANDARD, 0);
    }

    /** Creates a record with an explicit log level. */
    public EventLogRecord(EventContext context, Event event, EventLogLevel logLevel) {
        this(context, event, logLevel, 0);
    }

    /** Creates a record with an explicit log level and max field length for truncation. */
    public EventLogRecord(
            EventContext context, Event event, EventLogLevel logLevel, int maxFieldLength) {
        this.context = Objects.requireNonNull(context, "EventContext cannot be null");
        this.event = Objects.requireNonNull(event, "Event cannot be null");
        this.logLevel = logLevel != null ? logLevel : EventLogLevel.STANDARD;
        this.maxFieldLength = maxFieldLength;
    }

    public EventContext getContext() {
        return context;
    }

    public Event getEvent() {
        return event;
    }

    public EventLogLevel getLogLevel() {
        return logLevel;
    }

    public int getMaxFieldLength() {
        return maxFieldLength;
    }
}
