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
import org.apache.flink.metrics.Counter;

import javax.annotation.Nullable;

/**
 * Represents a record in the event log, containing the event context and the event itself.
 *
 * <p>This class is used to encapsulate the details of an event as it is logged, allowing for
 * structured logging and retrieval of event information.
 *
 * <p>The class uses custom JSON serialization/deserialization to handle polymorphic Event types by
 * leveraging the eventType information stored in the EventContext.
 *
 * <p>Each record carries a {@link EventLogLevel} that controls truncation behavior during
 * serialization, and a convenience copy of the event type string from the context.
 */
@JsonSerialize(using = EventLogRecordJsonSerializer.class)
@JsonDeserialize(using = EventLogRecordJsonDeserializer.class)
public class EventLogRecord {
    private final EventContext context;
    private final Event event;
    private final EventLogLevel logLevel;
    private final String eventType;
    @Nullable private final transient JsonTruncator truncator;
    @Nullable private final transient Counter truncatedEventsCounter;

    /**
     * Creates a record with default VERBOSE log level and no truncator. Used by the deserializer
     * and existing code paths that do not need level-aware serialization.
     *
     * @param context the event context
     * @param event the event
     */
    public EventLogRecord(EventContext context, Event event) {
        this(context, event, EventLogLevel.VERBOSE, null, null);
    }

    /**
     * Creates a record with the specified log level, truncator, and optional metric counter for
     * level-aware serialization.
     *
     * @param context the event context
     * @param event the event
     * @param logLevel the resolved log level for this event
     * @param truncator the truncator to apply when level is STANDARD, may be null
     * @param truncatedEventsCounter counter to increment when truncation occurs, may be null
     */
    public EventLogRecord(
            EventContext context,
            Event event,
            EventLogLevel logLevel,
            JsonTruncator truncator,
            Counter truncatedEventsCounter) {
        this.context = context;
        this.event = event;
        this.logLevel = logLevel != null ? logLevel : EventLogLevel.VERBOSE;
        this.eventType = context != null ? context.getEventType() : null;
        this.truncator = truncator;
        this.truncatedEventsCounter = truncatedEventsCounter;
    }

    /** Returns the event context. */
    public EventContext getContext() {
        return context;
    }

    /** Returns the event. */
    public Event getEvent() {
        return event;
    }

    /** Returns the log level for this record. */
    public EventLogLevel getLogLevel() {
        return logLevel;
    }

    /** Returns the event type string (convenience copy from context). */
    public String getEventType() {
        return eventType;
    }

    /** Returns the truncator, or null if no truncation should be applied. */
    public JsonTruncator getTruncator() {
        return truncator;
    }

    /** Returns the counter for tracking truncated events, or null if not set. */
    public Counter getTruncatedEventsCounter() {
        return truncatedEventsCounter;
    }
}
