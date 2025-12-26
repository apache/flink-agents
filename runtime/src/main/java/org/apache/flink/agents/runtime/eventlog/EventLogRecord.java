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

    public EventLogRecord(EventContext context, Event event) {
        this.context = context;
        this.event = event;
    }

    public EventContext getContext() {
        return context;
    }

    public Event getEvent() {
        return event;
    }
}
