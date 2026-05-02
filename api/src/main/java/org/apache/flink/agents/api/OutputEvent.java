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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

/**
 * Event representing a result from agent. By generating an OutputEvent, actions can emit output
 * data.
 */
public class OutputEvent extends Event {

    public static final String EVENT_TYPE = "_output_event";

    public OutputEvent(Object output) {
        super(EVENT_TYPE);
        setAttr("output", output);
    }

    @JsonCreator
    public OutputEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
    }

    /**
     * Reconstructs a typed OutputEvent from a base Event.
     *
     * @param event the base event containing the output data in attributes
     * @return a typed OutputEvent
     */
    public static OutputEvent fromEvent(Event event) {
        return new OutputEvent(event.getAttr("output"));
    }

    @JsonIgnore
    public Object getOutput() {
        return getAttr("output");
    }
}
