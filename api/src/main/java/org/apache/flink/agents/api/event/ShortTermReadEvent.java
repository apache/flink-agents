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

package org.apache.flink.agents.api.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

/**
 * Memory observation event: a read from short-term memory. See {@link MemoryEvent} for the
 * attribute contract.
 */
public class ShortTermReadEvent extends MemoryEvent {

    public static final String EVENT_TYPE = "_short_term_read_event";

    public ShortTermReadEvent(String key, Map<String, Object> value) {
        super(EVENT_TYPE, key, value);
    }

    /** Deserialization constructor ({@code @JsonCreator}: see {@link MemoryEvent}'s note). */
    @JsonCreator
    public ShortTermReadEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
    }
}
