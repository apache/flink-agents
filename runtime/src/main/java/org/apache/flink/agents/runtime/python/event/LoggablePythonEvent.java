/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.agents.runtime.python.event;

import org.apache.flink.agents.api.Event;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonCreator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

/**
 * A human-readable representation of {@link PythonEvent} for logging purposes.
 *
 * <p>Since {@link PythonEvent} stores Python objects as serialized byte arrays, it cannot be
 * directly displayed in logs in a meaningful way. This class converts the byte array to a
 * human-readable string representation while preserving the original event's metadata (id,
 * attributes, sourceTimestamp).
 *
 * <p>This class is used exclusively by {@link org.apache.flink.agents.api.logger.EventLogger} and
 * should not be used for other purposes such as state storage or event processing.
 */
public class LoggablePythonEvent extends Event {
    private final String event;
    private final String eventType;

    @JsonCreator
    public LoggablePythonEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes,
            @JsonProperty("event") String event,
            @JsonProperty("eventType") String eventType) {
        super(id, attributes);
        this.event = event;
        this.eventType = eventType;
    }

    public String getEvent() {
        return event;
    }

    public String getEventType() {
        return eventType;
    }
}
