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

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input for one video generation request.
 *
 * <p>Callers should not mutate model parameter collections during execution; implementations create
 * a new map when merging defaults or modifying parameters for a call.
 */
public final class VideoGenerationRequest {
    private final String prompt;
    private final List<MediaBlock> media;
    private final Map<String, Object> modelParameters;

    private VideoGenerationRequest(
            @Nullable String prompt,
            List<? extends MediaBlock> media,
            Map<String, Object> modelParameters) {
        this.prompt = prompt == null ? "" : prompt;
        this.media = media == null ? List.of() : List.copyOf(media);
        this.modelParameters =
                modelParameters == null || modelParameters.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(modelParameters));
    }

    public static VideoGenerationRequest of(String prompt) {
        return new VideoGenerationRequest(prompt, List.of(), Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(@Nullable String prompt) {
        return new Builder().prompt(prompt);
    }

    public String getPrompt() {
        return prompt;
    }

    public List<MediaBlock> getMedia() {
        return media;
    }

    public Map<String, Object> getModelParameters() {
        return modelParameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VideoGenerationRequest)) {
            return false;
        }
        VideoGenerationRequest that = (VideoGenerationRequest) o;
        return Objects.equals(prompt, that.prompt)
                && Objects.equals(media, that.media)
                && Objects.equals(modelParameters, that.modelParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prompt, media, modelParameters);
    }

    /** Builder for {@link VideoGenerationRequest}. */
    public static final class Builder {
        private String prompt = "";
        private List<MediaBlock> media = List.of();
        private final Map<String, Object> modelParameters = new LinkedHashMap<>();

        private Builder() {}

        public Builder prompt(@Nullable String prompt) {
            this.prompt = prompt == null ? "" : prompt;
            return this;
        }

        public Builder media(List<? extends MediaBlock> media) {
            this.media = media == null ? List.of() : List.copyOf(media);
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

        public VideoGenerationRequest build() {
            return new VideoGenerationRequest(prompt, media, modelParameters);
        }
    }
}
