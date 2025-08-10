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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Context for events in the Flink Agents API.
 *
 * <p>EventContext provides metadata about the event, including event key, timestamp, and additional
 * attributes that can be used to filter or categorize events.
 */
public class EventContext {
    private final Object key;
    private final Instant timestamp;
    private final Map<String, Object> attributes;

    public EventContext(Object key) {
        this.key = key;
        this.timestamp = Instant.now();
        this.attributes = new HashMap<>();
    }

    public Object getKey() {
        return key;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
