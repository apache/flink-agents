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

package org.apache.flink.agents.api.media;

import org.apache.flink.agents.api.utils.StringValidationUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Input for one speech synthesis request.
 *
 * <p>Callers should not mutate model parameter collections during execution; implementations create
 * a new map when merging defaults or modifying parameters for a call.
 */
public final class SpeechSynthesisRequest {
    private final String text;
    private final Map<String, Object> modelParameters;

    private SpeechSynthesisRequest(String text, Map<String, Object> modelParameters) {
        this.text = Objects.requireNonNull(text, "text");
        this.modelParameters =
                modelParameters == null || modelParameters.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(modelParameters));
    }

    public static SpeechSynthesisRequest of(String text) {
        return new SpeechSynthesisRequest(text, Map.of());
    }

    public static Builder builder(String text) {
        return new Builder(text);
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getModelParameters() {
        return modelParameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpeechSynthesisRequest)) {
            return false;
        }
        SpeechSynthesisRequest that = (SpeechSynthesisRequest) o;
        return Objects.equals(text, that.text)
                && Objects.equals(modelParameters, that.modelParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, modelParameters);
    }

    /** Builder for {@link SpeechSynthesisRequest}. */
    public static final class Builder {
        private final String text;
        private String voice;
        private final Map<String, Object> modelParameters = new LinkedHashMap<>();

        private Builder(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        /**
         * Set the synthesis voice. Takes precedence over any {@code "voice"} entry in {@link
         * #modelParameters(Map)} regardless of builder call order.
         */
        public Builder voice(String voice) {
            Objects.requireNonNull(voice, "voice");
            this.voice = StringValidationUtils.requireNonBlank(voice, "voice");
            return this;
        }

        public Builder modelParameter(String key, Object value) {
            this.modelParameters.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public Builder modelParameters(Map<String, Object> modelParameters) {
            if (modelParameters != null) {
                modelParameters.forEach(this::modelParameter);
            }
            return this;
        }

        public SpeechSynthesisRequest build() {
            if (voice == null) {
                return new SpeechSynthesisRequest(text, modelParameters);
            }
            Map<String, Object> merged = new LinkedHashMap<>(modelParameters);
            merged.put("voice", voice);
            return new SpeechSynthesisRequest(text, merged);
        }
    }
}
