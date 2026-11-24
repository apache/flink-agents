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

package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.media.AsyncJobModel;
import org.apache.flink.agents.api.media.BaseImageGenerationModelSetup;
import org.apache.flink.agents.api.media.BaseMediaModelSetup;
import org.apache.flink.agents.api.media.BaseSpeechSynthesisModelSetup;
import org.apache.flink.agents.api.media.BaseTranscriptionModelSetup;
import org.apache.flink.agents.api.media.BaseVideoGenerationModelSetup;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.SpeechSynthesisRequest;
import org.apache.flink.agents.api.media.TranscriptionRequest;
import org.apache.flink.agents.api.media.VideoGenerationRequest;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.utils.StringValidationUtils;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Factory creating Java {@link BaseMediaModelSetup} bridges around Python {@link
 * ResourceType#MEDIA_MODEL} setup instances.
 *
 * <p>Each created resource extends the task-specific {@code Base*ModelSetup} class and implements
 * {@link AsyncJobModel} if and only if the underlying Python resource supports async jobs.
 */
public final class PythonMediaModelSetup {
    static final String INSPECT_MEDIA_MODEL = "python_java_utils.inspect_media_model";

    static final String KEY_TASK = "task";
    static final String KEY_ASYNC = "async";
    static final String KEY_CONNECTION = "connection";
    static final String KEY_MODEL = "model";
    static final String KEY_VOICE = "voice";

    static final String TASK_SPEECH_SYNTHESIS = "speech_synthesis";
    static final String TASK_TRANSCRIPTION = "transcription";
    static final String TASK_IMAGE_GENERATION = "image_generation";
    static final String TASK_VIDEO_GENERATION = "video_generation";

    private PythonMediaModelSetup() {}

    /** Inspect the Python media model setup and return a matching typed Java wrapper. */
    public static BaseMediaModelSetup create(
            PythonResourceAdapter adapter,
            PyObject resource,
            ResourceDescriptor descriptor,
            @Nullable ResourceContext resourceContext) {
        Objects.requireNonNull(descriptor, "descriptor");
        Map<?, ?> metadata = (Map<?, ?>) adapter.invoke(INSPECT_MEDIA_MODEL, resource);
        if (metadata == null) {
            throw new IllegalStateException("Invalid MediaModel inspection result from Python");
        }
        Object taskValue = metadata.get(KEY_TASK);
        Object asyncValue = metadata.get(KEY_ASYNC);
        if (!(taskValue instanceof String) || !(asyncValue instanceof Boolean)) {
            throw new IllegalStateException("Invalid MediaModel inspection result from Python");
        }
        String task = (String) taskValue;
        boolean async = (Boolean) asyncValue;
        final String connectionName;
        final String model;
        @Nullable final String voice;
        try {
            connectionName =
                    StringValidationUtils.requireNonBlank(
                            metadata.get(KEY_CONNECTION), KEY_CONNECTION);
            model = StringValidationUtils.requireNonBlank(metadata.get(KEY_MODEL), KEY_MODEL);
            voice =
                    TASK_SPEECH_SYNTHESIS.equals(task)
                            ? StringValidationUtils.optionalNonBlank(
                                    metadata.get(KEY_VOICE), KEY_VOICE)
                            : null;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid MediaModel inspection result from Python", e);
        }
        ResourceDescriptor effectiveDescriptor =
                descriptorWithInspectedMetadata(descriptor, connectionName, model, voice);
        PythonMediaBridge bridge =
                new PythonMediaBridge(
                        adapter, resource, task, effectiveDescriptor, resourceContext);
        switch (task) {
            case TASK_SPEECH_SYNTHESIS:
                return async ? new AsyncSpeech(bridge) : new Speech(bridge);
            case TASK_TRANSCRIPTION:
                return async ? new AsyncTranscription(bridge) : new Transcription(bridge);
            case TASK_IMAGE_GENERATION:
                return async ? new AsyncImage(bridge) : new Image(bridge);
            case TASK_VIDEO_GENERATION:
                return async ? new AsyncVideo(bridge) : new Video(bridge);
            default:
                throw new IllegalStateException("Invalid MediaModel inspection result from Python");
        }
    }

    private static ResourceDescriptor descriptorWithInspectedMetadata(
            ResourceDescriptor descriptor,
            String connectionName,
            String model,
            @Nullable String voice) {
        Map<String, Object> mergedArgs =
                descriptor.getInitialArguments() != null
                        ? new HashMap<>(descriptor.getInitialArguments())
                        : new HashMap<>();
        mergedArgs.put(KEY_CONNECTION, connectionName);
        mergedArgs.put(KEY_MODEL, model);
        if (voice != null) {
            mergedArgs.put(KEY_VOICE, voice);
        } else {
            mergedArgs.remove(KEY_VOICE);
        }
        if (descriptor instanceof PythonResourceDescriptor) {
            return new PythonResourceDescriptor(
                    descriptor.getModule(), descriptor.getClazz(), mergedArgs);
        }
        return new ResourceDescriptor(descriptor.getModule(), descriptor.getClazz(), mergedArgs);
    }

    private static class Speech extends BaseSpeechSynthesisModelSetup
            implements PythonResourceWrapper {
        protected final PythonMediaBridge bridge;

        private Speech(PythonMediaBridge bridge) {
            super(bridge.getDescriptor(), bridge.getResourceContext());
            this.bridge = bridge;
        }

        @Override
        public void open() throws Exception {
            bridge.open();
        }

        @Override
        public void close() throws Exception {
            bridge.close();
        }

        @Override
        public Object getPythonResource() {
            return bridge.getPythonResource();
        }

        @Override
        public PythonResourceAdapter getPythonResourceAdapter() {
            return bridge.getPythonResourceAdapter();
        }

        @Override
        public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
            super.setMetricGroup(metricGroup);
            setPythonResourceMetricGroup(metricGroup);
        }

        @Override
        public MediaResult<AudioBlock> synthesize(
                SpeechSynthesisRequest request, @Nullable Duration timeout) {
            return bridge.generate(request, timeout, PythonMediaBridge.SPEECH);
        }
    }

    private static final class AsyncSpeech extends Speech
            implements AsyncJobModel<SpeechSynthesisRequest, MediaResult<AudioBlock>> {
        private AsyncSpeech(PythonMediaBridge bridge) {
            super(bridge);
        }

        @Override
        public MediaResult<AudioBlock> synthesize(
                SpeechSynthesisRequest request, @Nullable Duration timeout) {
            return bridge.submitAndWait(this, request, timeout, PythonMediaBridge.SPEECH);
        }

        @Override
        public MediaJob submit(SpeechSynthesisRequest request) {
            return bridge.submit(request, PythonMediaBridge.SPEECH);
        }

        @Override
        public MediaJobState<MediaResult<AudioBlock>> fetch(MediaJob job) {
            return bridge.fetch(job, AudioBlock.class);
        }

        @Override
        public void cancel(MediaJob job) {
            bridge.cancel(job);
        }

        @Override
        public MediaResult<AudioBlock> wait(
                MediaJob job, @Nullable Duration timeout, Duration pollInterval) {
            return bridge.waitJob(this::fetch, job, timeout, pollInterval);
        }
    }

    private static class Transcription extends BaseTranscriptionModelSetup
            implements PythonResourceWrapper {
        protected final PythonMediaBridge bridge;

        private Transcription(PythonMediaBridge bridge) {
            super(bridge.getDescriptor(), bridge.getResourceContext());
            this.bridge = bridge;
        }

        @Override
        public void open() throws Exception {
            bridge.open();
        }

        @Override
        public void close() throws Exception {
            bridge.close();
        }

        @Override
        public Object getPythonResource() {
            return bridge.getPythonResource();
        }

        @Override
        public PythonResourceAdapter getPythonResourceAdapter() {
            return bridge.getPythonResourceAdapter();
        }

        @Override
        public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
            super.setMetricGroup(metricGroup);
            setPythonResourceMetricGroup(metricGroup);
        }

        @Override
        public MediaResult<TextBlock> transcribe(
                TranscriptionRequest request, @Nullable Duration timeout) {
            return bridge.generate(request, timeout, PythonMediaBridge.TRANSCRIPTION);
        }
    }

    private static final class AsyncTranscription extends Transcription
            implements AsyncJobModel<TranscriptionRequest, MediaResult<TextBlock>> {
        private AsyncTranscription(PythonMediaBridge bridge) {
            super(bridge);
        }

        @Override
        public MediaResult<TextBlock> transcribe(
                TranscriptionRequest request, @Nullable Duration timeout) {
            return bridge.submitAndWait(this, request, timeout, PythonMediaBridge.TRANSCRIPTION);
        }

        @Override
        public MediaJob submit(TranscriptionRequest request) {
            return bridge.submit(request, PythonMediaBridge.TRANSCRIPTION);
        }

        @Override
        public MediaJobState<MediaResult<TextBlock>> fetch(MediaJob job) {
            return bridge.fetch(job, TextBlock.class);
        }

        @Override
        public void cancel(MediaJob job) {
            bridge.cancel(job);
        }

        @Override
        public MediaResult<TextBlock> wait(
                MediaJob job, @Nullable Duration timeout, Duration pollInterval) {
            return bridge.waitJob(this::fetch, job, timeout, pollInterval);
        }
    }

    private static class Image extends BaseImageGenerationModelSetup
            implements PythonResourceWrapper {
        protected final PythonMediaBridge bridge;

        private Image(PythonMediaBridge bridge) {
            super(bridge.getDescriptor(), bridge.getResourceContext());
            this.bridge = bridge;
        }

        @Override
        public void open() throws Exception {
            bridge.open();
        }

        @Override
        public void close() throws Exception {
            bridge.close();
        }

        @Override
        public Object getPythonResource() {
            return bridge.getPythonResource();
        }

        @Override
        public PythonResourceAdapter getPythonResourceAdapter() {
            return bridge.getPythonResourceAdapter();
        }

        @Override
        public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
            super.setMetricGroup(metricGroup);
            setPythonResourceMetricGroup(metricGroup);
        }

        @Override
        public MediaResult<ImageBlock> generate(
                ImageGenerationRequest request, @Nullable Duration timeout) {
            return bridge.generate(request, timeout, PythonMediaBridge.IMAGE);
        }
    }

    private static final class AsyncImage extends Image
            implements AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> {
        private AsyncImage(PythonMediaBridge bridge) {
            super(bridge);
        }

        @Override
        public MediaResult<ImageBlock> generate(
                ImageGenerationRequest request, @Nullable Duration timeout) {
            return bridge.submitAndWait(this, request, timeout, PythonMediaBridge.IMAGE);
        }

        @Override
        public MediaJob submit(ImageGenerationRequest request) {
            return bridge.submit(request, PythonMediaBridge.IMAGE);
        }

        @Override
        public MediaJobState<MediaResult<ImageBlock>> fetch(MediaJob job) {
            return bridge.fetch(job, ImageBlock.class);
        }

        @Override
        public void cancel(MediaJob job) {
            bridge.cancel(job);
        }

        @Override
        public MediaResult<ImageBlock> wait(
                MediaJob job, @Nullable Duration timeout, Duration pollInterval) {
            return bridge.waitJob(this::fetch, job, timeout, pollInterval);
        }
    }

    private static class Video extends BaseVideoGenerationModelSetup
            implements PythonResourceWrapper {
        protected final PythonMediaBridge bridge;

        private Video(PythonMediaBridge bridge) {
            super(bridge.getDescriptor(), bridge.getResourceContext());
            this.bridge = bridge;
        }

        @Override
        public void open() throws Exception {
            bridge.open();
        }

        @Override
        public void close() throws Exception {
            bridge.close();
        }

        @Override
        public Object getPythonResource() {
            return bridge.getPythonResource();
        }

        @Override
        public PythonResourceAdapter getPythonResourceAdapter() {
            return bridge.getPythonResourceAdapter();
        }

        @Override
        public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
            super.setMetricGroup(metricGroup);
            setPythonResourceMetricGroup(metricGroup);
        }

        @Override
        public MediaResult<VideoBlock> generate(
                VideoGenerationRequest request, @Nullable Duration timeout) {
            return bridge.generate(request, timeout, PythonMediaBridge.VIDEO);
        }
    }

    private static final class AsyncVideo extends Video
            implements AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> {
        private AsyncVideo(PythonMediaBridge bridge) {
            super(bridge);
        }

        @Override
        public MediaResult<VideoBlock> generate(
                VideoGenerationRequest request, @Nullable Duration timeout) {
            return bridge.submitAndWait(this, request, timeout, PythonMediaBridge.VIDEO);
        }

        @Override
        public MediaJob submit(VideoGenerationRequest request) {
            return bridge.submit(request, PythonMediaBridge.VIDEO);
        }

        @Override
        public MediaJobState<MediaResult<VideoBlock>> fetch(MediaJob job) {
            return bridge.fetch(job, VideoBlock.class);
        }

        @Override
        public void cancel(MediaJob job) {
            bridge.cancel(job);
        }

        @Override
        public MediaResult<VideoBlock> wait(
                MediaJob job, @Nullable Duration timeout, Duration pollInterval) {
            return bridge.waitJob(this::fetch, job, timeout, pollInterval);
        }
    }
}
