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

package org.apache.flink.agents.api.chat.model.routing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The structured result of a {@link RoutingStrategy}. Carries the selected model name plus optional
 * explanation (reason, score, metadata), or signals abstention so the router falls back to its
 * default model.
 *
 * <p>Returning a name that is not one of the router's candidates is an <b>invalid</b> decision and
 * is failed clearly by the router (not silently defaulted). Abstention ({@link #abstain()}) is the
 * intended way to defer to the default model.
 *
 * <p>JSON-serializable so it can be persisted/replayed as a durable {@code "route"} call result.
 */
public final class RoutingDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String selectedModel;
    private final boolean abstain;
    private final String reason;
    private final Double score;
    private final Map<String, Object> metadata;
    private final Double decisionMs;

    @JsonCreator
    public RoutingDecision(
            @JsonProperty("selected_model") String selectedModel,
            @JsonProperty("abstain") boolean abstain,
            @JsonProperty("reason") String reason,
            @JsonProperty("score") Double score,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("decision_ms") Double decisionMs) {
        // Invariants hold on every construction path — including JSON deserialization, which is
        // the path durable replay takes.
        if (abstain && selectedModel != null) {
            throw new IllegalArgumentException(
                    "An abstaining decision must not carry a selected model.");
        }
        if (!abstain && (selectedModel == null || selectedModel.isEmpty())) {
            throw new IllegalArgumentException(
                    "A non-abstain decision requires a selected model; use abstain() to defer.");
        }
        this.selectedModel = selectedModel;
        this.abstain = abstain;
        this.reason = reason;
        this.score = score;
        this.metadata =
                metadata == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(metadata));
        this.decisionMs = decisionMs;
    }

    /** A decision selecting the given candidate model. */
    public static RoutingDecision of(String selectedModel) {
        return new RoutingDecision(selectedModel, false, null, null, Collections.emptyMap(), null);
    }

    /** A decision to abstain, deferring to the router's default model. */
    public static RoutingDecision abstain() {
        return new RoutingDecision(null, true, null, null, Collections.emptyMap(), null);
    }

    /**
     * Copy of this decision stamped with the strategy's wall-clock time. Framework-recorded inside
     * the durable {@code "route"} call, so a replayed decision reports its <em>original</em>
     * latency, not the replay's.
     */
    public RoutingDecision withDecisionMs(double decisionMs) {
        return new RoutingDecision(selectedModel, abstain, reason, score, metadata, decisionMs);
    }

    /** Start building a rich decision (with reason/score/metadata) for the given model. */
    public static Builder builder(String selectedModel) {
        return new Builder(selectedModel);
    }

    @Nullable
    @JsonProperty("selected_model")
    public String getSelectedModel() {
        return selectedModel;
    }

    public boolean isAbstain() {
        return abstain;
    }

    @Nullable
    public String getReason() {
        return reason;
    }

    @Nullable
    public Double getScore() {
        return score;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** Strategy wall-clock time in milliseconds, if recorded (see {@link #withDecisionMs}). */
    @Nullable
    @JsonProperty("decision_ms")
    public Double getDecisionMs() {
        return decisionMs;
    }

    @Override
    public String toString() {
        return abstain
                ? "RoutingDecision{abstain}"
                : "RoutingDecision{selectedModel='" + selectedModel + "', reason=" + reason + "}";
    }

    /** Builder for a rich {@link RoutingDecision}. */
    public static final class Builder {
        private final String selectedModel;
        private String reason;
        private Double score;
        private final Map<String, Object> metadata = new HashMap<>();

        private Builder(String selectedModel) {
            if (selectedModel == null || selectedModel.isEmpty()) {
                throw new IllegalArgumentException(
                        "Selected model must be non-null and non-empty.");
            }
            this.selectedModel = selectedModel;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public RoutingDecision build() {
            return new RoutingDecision(selectedModel, false, reason, score, metadata, null);
        }
    }
}
