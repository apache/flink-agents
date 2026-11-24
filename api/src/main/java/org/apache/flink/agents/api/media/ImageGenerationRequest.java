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

import org.apache.flink.agents.api.chat.messages.ImageBlock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input for one image generation or editing request.
 *
 * <p>Callers should not mutate model parameter collections during execution; implementations create
 * a new map when merging defaults or modifying parameters for a call.
 */
public final class ImageGenerationRequest {
    private final String prompt;
    private final List<ImageBlock> images;
    private final Map<String, Object> modelParameters;

    private ImageGenerationRequest(
            String prompt, List<ImageBlock> images, Map<String, Object> modelParameters) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.images = images == null ? List.of() : List.copyOf(images);
        this.modelParameters =
                modelParameters == null || modelParameters.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(modelParameters));
    }

    public static ImageGenerationRequest of(String prompt) {
        return new ImageGenerationRequest(prompt, List.of(), Map.of());
    }

    public static Builder builder(String prompt) {
        return new Builder(prompt);
    }

    public String getPrompt() {
        return prompt;
    }

    public List<ImageBlock> getImages() {
        return images;
    }

    public Map<String, Object> getModelParameters() {
        return modelParameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageGenerationRequest)) {
            return false;
        }
        ImageGenerationRequest that = (ImageGenerationRequest) o;
        return Objects.equals(prompt, that.prompt)
                && Objects.equals(images, that.images)
                && Objects.equals(modelParameters, that.modelParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prompt, images, modelParameters);
    }

    /** Builder for {@link ImageGenerationRequest}. */
    public static final class Builder {
        private final String prompt;
        private List<ImageBlock> images = List.of();
        private final Map<String, Object> modelParameters = new LinkedHashMap<>();

        private Builder(String prompt) {
            this.prompt = Objects.requireNonNull(prompt, "prompt");
        }

        public Builder images(List<ImageBlock> images) {
            this.images = images == null ? List.of() : List.copyOf(images);
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

        public ImageGenerationRequest build() {
            return new ImageGenerationRequest(prompt, images, modelParameters);
        }
    }
}
