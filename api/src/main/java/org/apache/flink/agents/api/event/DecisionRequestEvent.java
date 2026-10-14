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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.decision.DecisionRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Requests a decision from a registered DecisionModel setup resource. */
public class DecisionRequestEvent extends Event {
    public static final String EVENT_TYPE = "_decision_request_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<BuiltInAttribute> ATTRIBUTE_SCHEMA =
            List.of(
                    BuiltInAttribute.required("model", String.class),
                    BuiltInAttribute.requiredUntyped("request"));

    /** The model is the registered resource name, not a provider model identifier. */
    public DecisionRequestEvent(String model, DecisionRequest request) {
        super(EVENT_TYPE);
        setAttr("model", Objects.requireNonNull(model, "model"));
        setAttr("request", Objects.requireNonNull(request, "request"));
    }

    @JsonCreator
    public DecisionRequestEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeAttributes(attributes));
    }

    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        Object request = attributes.get("request");
        if (request instanceof Map) {
            request = MAPPER.convertValue(request, DecisionRequest.class);
            attributes.put("request", request);
        }
        if (!(request instanceof DecisionRequest)) {
            throw new IllegalArgumentException(
                    "request must be a DecisionRequest or its serialized map");
        }
        return attributes;
    }

    /** Restores the typed request while preserving event identity, lineage and attachments. */
    public static DecisionRequestEvent fromEvent(Event event) {
        validateAttributeSchema(EVENT_TYPE, event.getAttributes(), ATTRIBUTE_SCHEMA);
        return reconstructFrom(event, DecisionRequestEvent::new);
    }

    @JsonIgnore
    public String getModel() {
        return (String) getAttr("model");
    }

    @JsonIgnore
    public DecisionRequest getRequest() {
        return (DecisionRequest) getAttr("request");
    }
}
