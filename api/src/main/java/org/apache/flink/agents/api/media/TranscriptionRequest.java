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

import org.apache.flink.agents.api.chat.messages.MediaBlock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Input for one transcription request.
 *
 * <p>Callers should not mutate model parameter collections during execution; implementations create
 * a new map when merging defaults or modifying parameters for a call.
 */
public final class TranscriptionRequest {
    private final MediaBlock audio;
    private final Map<String, Object> modelParameters;

    private TranscriptionRequest(MediaBlock audio, Map<String, Object> modelParameters) {
        this.audio = Objects.requireNonNull(audio, "audio");
        this.modelParameters =
                modelParameters == null || modelParameters.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(modelParameters));
    }

    public static TranscriptionRequest of(MediaBlock audio) {
        return new TranscriptionRequest(audio, Map.of());
    }

    public static Builder builder(MediaBlock audio) {
        return new Builder(audio);
    }

    public MediaBlock getAudio() {
        return audio;
    }

    public Map<String, Object> getModelParameters() {
        return modelParameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranscriptionRequest)) {
            return false;
        }
        TranscriptionRequest that = (TranscriptionRequest) o;
        return Objects.equals(audio, that.audio)
                && Objects.equals(modelParameters, that.modelParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(audio, modelParameters);
    }

    /** Builder for {@link TranscriptionRequest}. */
    public static final class Builder {
        private final MediaBlock audio;
        private final Map<String, Object> modelParameters = new LinkedHashMap<>();

        private Builder(MediaBlock audio) {
            this.audio = Objects.requireNonNull(audio, "audio");
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

        public TranscriptionRequest build() {
            return new TranscriptionRequest(audio, modelParameters);
        }
    }
}
