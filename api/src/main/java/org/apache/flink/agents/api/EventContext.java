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

package org.apache.flink.agents.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Contextual information about an event, such as its type and timestamp. */
public class EventContext {
    /**
     * The routing key for the event. For subclassed events this is the FQN class name; for unified
     * events this is the user-defined type string.
     */
    private final String eventType;

    /**
     * The fully qualified Java class name used for deserialization. For unified events (no
     * subclass), this is {@code "org.apache.flink.agents.api.Event"}.
     */
    private final String eventClass;

    // Timestamp of when the event occurred
    private final String timestamp;

    public EventContext(Event event) {
        this(event.getType(), event.getClass().getName(), Instant.now().toString());
    }

    @JsonCreator
    public EventContext(
            @JsonProperty("eventType") String eventType,
            @JsonProperty("eventClass") String eventClass,
            @JsonProperty("timestamp") String timestamp) {
        this.eventType = eventType;
        this.eventClass = eventClass != null ? eventClass : eventType;
        this.timestamp = timestamp;
    }

    public EventContext(String eventType, String timestamp) {
        this(eventType, eventType, timestamp);
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventClass() {
        return eventClass;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
