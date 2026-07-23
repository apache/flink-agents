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

/** Runtime context for one observed event occurrence. */
public class EventContext {
    /** The event type observed by the runtime, matching {@link Event#getType()}. */
    private final String eventType;

    /** Timestamp of when the event occurrence was observed by the runtime. */
    private final String timestamp;

    public EventContext(Event event) {
        this(event.getType(), Instant.now().toString());
    }

    @JsonCreator
    public EventContext(
            @JsonProperty("eventType") String eventType,
            @JsonProperty("timestamp") String timestamp) {
        this.eventType = eventType;
        this.timestamp = timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
