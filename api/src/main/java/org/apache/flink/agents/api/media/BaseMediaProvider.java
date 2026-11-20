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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A media provider that binds capabilities to models and owns their shared client.
 *
 * <p>Each binding accepts a map of default model parameters. Whether a bound capability runs
 * synchronously or exposes resumable provider jobs via {@link AsyncJobModel} is determined by the
 * underlying provider implementation. Factory methods should return lightweight capability views
 * without independent mutable state so the same {@code (model, modelParameters)} binding can be
 * recreated safely; shared state belongs on the provider itself (or in a persisted {@link
 * MediaJob}). Cross-language Java-to-Python bridges recreate bindings from immutable configuration
 * rather than retaining child Python object handles. The bound model shares this provider's
 * lifetime and must not be used after the provider closes.
 */
public abstract class BaseMediaProvider extends Resource {
    protected BaseMediaProvider(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.MEDIA_PROVIDER;
    }

    /** Bind a speech model using the provider's default options. */
    public SpeechSynthesisModel speech(String model) {
        return speech(model, Map.of());
    }

    /** Bind a speech model with a default voice. */
    public SpeechSynthesisModel speech(String model, String voice) {
        return speech(model, voice, Map.of());
    }

    /** Bind a speech model with a default voice and additional default model parameters. */
    public SpeechSynthesisModel speech(
            String model, String voice, Map<String, Object> modelParameters) {
        Map<String, Object> merged =
                modelParameters == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(modelParameters);
        merged.put("voice", Objects.requireNonNull(voice, "voice"));
        return speech(model, merged);
    }

    /** Bind a speech model with default model parameters, such as its voice. */
    public SpeechSynthesisModel speech(String model, Map<String, Object> modelParameters) {
        throw new UnsupportedOperationException(
                "SpeechSynthesis is not supported by this provider");
    }

    /** Bind a transcription model using the provider's default options. */
    public TranscriptionModel transcription(String model) {
        return transcription(model, Map.of());
    }

    /** Bind a transcription model with default model parameters. */
    public TranscriptionModel transcription(String model, Map<String, Object> modelParameters) {
        throw new UnsupportedOperationException("Transcription is not supported by this provider");
    }

    /** Bind an image model using the provider's default options. */
    public ImageGenerationModel image(String model) {
        return image(model, Map.of());
    }

    /** Bind an image model with default model parameters. */
    public ImageGenerationModel image(String model, Map<String, Object> modelParameters) {
        throw new UnsupportedOperationException(
                "Image generation is not supported by this provider");
    }

    /** Bind a video model using the provider's default options. */
    public VideoGenerationModel video(String model) {
        return video(model, Map.of());
    }

    /** Bind a video model with default model parameters. */
    public VideoGenerationModel video(String model, Map<String, Object> modelParameters) {
        throw new UnsupportedOperationException(
                "Video generation is not supported by this provider");
    }
}
