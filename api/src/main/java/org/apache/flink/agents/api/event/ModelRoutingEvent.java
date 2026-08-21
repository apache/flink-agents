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
import org.apache.flink.agents.api.Event;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Observability-only event recording a model-routing decision.
 *
 * <p>Emitted by {@code ChatModelAction} after a {@code MODEL_ROUTER} selects a concrete chat model
 * and before the selected model is invoked. It is a record for logging, tracing, and evaluation: it
 * has <b>no built-in consumer</b> and does <b>not</b> drive dispatch. Removing every listener of
 * this event does not change which model runs.
 */
public class ModelRoutingEvent extends Event {

    public static final String EVENT_TYPE = "_model_routing_event";

    /** Where the final selected model came from. */
    public static final String SOURCE_STRATEGY = "strategy";

    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_FALLBACK = "fallback";

    public ModelRoutingEvent(
            UUID requestId,
            String router,
            List<String> candidates,
            String selectedModel,
            String decisionSource,
            boolean fallbackEnabled,
            @Nullable String reason,
            @Nullable Double score,
            @Nullable Map<String, Object> metadata,
            @Nullable Double decisionMs) {
        super(EVENT_TYPE);
        setAttr("request_id", requestId);
        setAttr("router", router);
        setAttr("candidates", new ArrayList<>(candidates));
        setAttr("selected_model", selectedModel);
        setAttr("decision_source", decisionSource);
        setAttr("fallback_enabled", fallbackEnabled);
        if (reason != null) {
            setAttr("reason", reason);
        }
        if (score != null) {
            setAttr("score", score);
        }
        setAttr("metadata", mutableRoutingValue(metadata == null ? new HashMap<>() : metadata));
        if (decisionMs != null) {
            setAttr("decision_ms", decisionMs);
        }
    }

    @JsonCreator
    public ModelRoutingEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeAttributes(attributes));
    }

    /** Convert the {@code request_id} back to a {@link UUID} after JSON deserialization. */
    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        Map<String, Object> normalized =
                attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        Object rawId = normalized.get("request_id");
        if (rawId instanceof String) {
            normalized.put("request_id", UUID.fromString((String) rawId));
        }
        Object candidates = normalized.get("candidates");
        if (candidates instanceof Collection<?>) {
            normalized.put("candidates", mutableRoutingValue(candidates));
        }
        Object metadata = normalized.get("metadata");
        if (metadata instanceof Map<?, ?>) {
            normalized.put("metadata", mutableRoutingValue(metadata));
        }
        return normalized;
    }

    /** Reconstructs a typed ModelRoutingEvent from a base Event. */
    public static ModelRoutingEvent fromEvent(Event event) {
        ModelRoutingEvent result =
                new ModelRoutingEvent(event.getId(), new HashMap<>(event.getAttributes()));
        if (event.hasSourceTimestamp()) {
            result.setSourceTimestamp(event.getSourceTimestamp());
        }
        return result;
    }

    @JsonIgnore
    public UUID getRequestId() {
        Object val = getAttr("request_id");
        if (val instanceof String) {
            return UUID.fromString((String) val);
        }
        return (UUID) val;
    }

    @JsonIgnore
    public String getRouter() {
        return (String) getAttr("router");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<String> getCandidates() {
        return (List<String>) getAttr("candidates");
    }

    @JsonIgnore
    public String getSelectedModel() {
        return (String) getAttr("selected_model");
    }

    @JsonIgnore
    public String getDecisionSource() {
        return (String) getAttr("decision_source");
    }

    /** Whether the router was configured with fallback (not whether fallback happened). */
    @JsonIgnore
    public boolean isFallbackEnabled() {
        Object value = getAttr("fallback_enabled");
        return value instanceof Boolean && (Boolean) value;
    }

    @JsonIgnore
    @Nullable
    public String getReason() {
        return (String) getAttr("reason");
    }

    @JsonIgnore
    @Nullable
    public Double getScore() {
        Object value = getAttr("score");
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMetadata() {
        Map<String, Object> metadata = (Map<String, Object>) getAttr("metadata");
        return metadata == null ? new HashMap<>() : metadata;
    }

    /** Wall-clock time spent resolving the routing decision, in milliseconds (if recorded). */
    @JsonIgnore
    @Nullable
    public Double getDecisionMs() {
        Object value = getAttr("decision_ms");
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private static Object mutableRoutingValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            ((Map<?, ?>) value)
                    .forEach((key, nestedValue) -> copy.put(key, mutableRoutingValue(nestedValue)));
            return copy;
        }
        if (value instanceof Collection<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object nestedValue : (Collection<?>) value) {
                copy.add(mutableRoutingValue(nestedValue));
            }
            return copy;
        }
        return value;
    }
}
