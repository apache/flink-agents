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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.media.AsyncJobModel;
import org.apache.flink.agents.api.media.BaseMediaProvider;
import org.apache.flink.agents.api.media.ImageGenerationModel;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobException;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaJobTimeoutException;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.SpeechSynthesisModel;
import org.apache.flink.agents.api.media.SpeechSynthesisRequest;
import org.apache.flink.agents.api.media.TranscriptionModel;
import org.apache.flink.agents.api.media.TranscriptionRequest;
import org.apache.flink.agents.api.media.VideoGenerationModel;
import org.apache.flink.agents.api.media.VideoGenerationRequest;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Java access to a Python media provider. */
public final class PythonMediaProvider extends BaseMediaProvider implements PythonResourceWrapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Capability<SpeechSynthesisRequest, AudioBlock> SPEECH =
            new Capability<>(
                    AudioBlock.class,
                    SpeechSynthesisRequest::getText,
                    SpeechSynthesisRequest::getModelParameters);
    private static final Capability<TranscriptionRequest, TextBlock> TRANSCRIPTION =
            new Capability<>(
                    TextBlock.class,
                    TranscriptionRequest::getAudio,
                    TranscriptionRequest::getModelParameters);
    private static final Capability<ImageGenerationRequest, ImageBlock> IMAGE =
            new Capability<>(
                    ImageBlock.class,
                    req -> Map.of("prompt", req.getPrompt(), "images", req.getImages()),
                    ImageGenerationRequest::getModelParameters);
    private static final Capability<VideoGenerationRequest, VideoBlock> VIDEO =
            new Capability<>(
                    VideoBlock.class,
                    req -> Map.of("prompt", req.getPrompt(), "media", req.getMedia()),
                    VideoGenerationRequest::getModelParameters);

    private final PythonResourceAdapter adapter;
    private final PyObject resource;
    private volatile boolean closed;

    public PythonMediaProvider(
            PythonResourceAdapter adapter,
            PyObject resource,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.adapter = adapter;
        this.resource = resource;
    }

    @Override
    public SpeechSynthesisModel speech(String model, Map<String, Object> modelParameters) {
        return wrapSpeech(bind("speech_synthesis", model, bindingOptions(modelParameters)));
    }

    @Override
    public SpeechSynthesisModel speech(
            String model, String voice, Map<String, Object> modelParameters) {
        return wrapSpeech(
                bind("speech_synthesis", model, speechBindingOptions(voice, modelParameters)));
    }

    private static SpeechSynthesisModel wrapSpeech(Binding binding) {
        return binding.async
                ? new AsyncSpeech(binding)
                : (request, timeout) -> binding.generate(request, timeout, SPEECH);
    }

    @Override
    public TranscriptionModel transcription(String model, Map<String, Object> modelParameters) {
        Binding binding = bind("transcription", model, bindingOptions(modelParameters));
        return binding.async
                ? new AsyncTranscription(binding)
                : (request, timeout) -> binding.generate(request, timeout, TRANSCRIPTION);
    }

    @Override
    public ImageGenerationModel image(String model, Map<String, Object> modelParameters) {
        Binding binding = bind("image_generation", model, bindingOptions(modelParameters));
        return binding.async
                ? new AsyncImage(binding)
                : (request, timeout) -> binding.generate(request, timeout, IMAGE);
    }

    @Override
    public VideoGenerationModel video(String model, Map<String, Object> modelParameters) {
        Binding binding = bind("video_generation", model, bindingOptions(modelParameters));
        return binding.async
                ? new AsyncVideo(binding)
                : (request, timeout) -> binding.generate(request, timeout, VIDEO);
    }

    private static Map<String, Object> bindingOptions(
            @Nullable Map<String, Object> modelParameters) {
        return (modelParameters == null || modelParameters.isEmpty())
                ? Map.of()
                : Map.of("model_parameters", modelParameters);
    }

    private static Map<String, Object> speechBindingOptions(
            String voice, @Nullable Map<String, Object> modelParameters) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("voice", Objects.requireNonNull(voice, "voice"));
        if (modelParameters != null && !modelParameters.isEmpty()) {
            options.put("model_parameters", modelParameters);
        }
        return options;
    }

    static void validatePositiveDuration(@Nullable Duration duration, String name) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static void validateNonNegativeDuration(@Nullable Duration duration, String name) {
        if (duration != null && duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static @Nullable Double toSeconds(@Nullable Duration duration) {
        return duration == null
                ? null
                : duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private Binding bind(String task, String model, Map<String, Object> options) {
        checkOpen();
        String nonNullModel = Objects.requireNonNull(model, "model");
        String optionsJson = json(options);
        Object result =
                adapter.invoke(
                        "python_java_utils.validate_media_binding",
                        resource,
                        task,
                        nonNullModel,
                        optionsJson);
        if (!(result instanceof Boolean)) {
            throw new IllegalStateException(
                    "Invalid MediaProvider binding validation result from Python");
        }
        return new Binding(task, nonNullModel, optionsJson, (Boolean) result);
    }

    @Override
    public void open() {
        checkOpen();
        adapter.callMethod(resource, "open", Map.of());
    }

    @Override
    public Object getPythonResource() {
        return resource;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try (resource) {
            adapter.callMethod(resource, "close", Map.of());
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Media provider is closed");
        }
    }

    private static final class Capability<Req, T extends DataContentBlock> {
        private final Class<T> blockType;
        private final Function<Req, Object> payload;
        private final Function<Req, Map<String, Object>> modelParameters;

        private Capability(
                Class<T> blockType,
                Function<Req, Object> payload,
                Function<Req, Map<String, Object>> modelParameters) {
            this.blockType = blockType;
            this.payload = payload;
            this.modelParameters = modelParameters;
        }
    }

    /** Immutable configuration, not a Python object handle or a provider job. */
    private final class Binding {
        private final String task;
        private final String model;
        private final String optionsJson;
        private final boolean async;

        private Binding(String task, String model, String optionsJson, boolean async) {
            this.task = task;
            this.model = model;
            this.optionsJson = optionsJson;
            this.async = async;
        }

        private Object invoke(String operation, Object... arguments) {
            checkOpen();
            Object[] all = new Object[4 + arguments.length];
            all[0] = resource;
            all[1] = task;
            all[2] = model;
            all[3] = optionsJson;
            System.arraycopy(arguments, 0, all, 4, arguments.length);
            return adapter.invoke("python_java_utils." + operation, all);
        }

        private <Req, T extends DataContentBlock> MediaResult<T> generate(
                Req request, @Nullable Duration timeout, Capability<Req, T> capability) {
            checkOpen();
            Objects.requireNonNull(request, "request");
            validatePositiveDuration(timeout, "timeout");
            return read(
                    invoke(
                            "call_media_generate",
                            json(capability.payload.apply(request)),
                            toSeconds(timeout),
                            json(capability.modelParameters.apply(request))),
                    resultType(capability.blockType));
        }

        private <Req> MediaJob submit(Req request, Capability<Req, ?> capability) {
            checkOpen();
            Objects.requireNonNull(request, "request");
            return read(
                    invoke(
                            "call_media_submit",
                            json(capability.payload.apply(request)),
                            json(capability.modelParameters.apply(request))),
                    MAPPER.constructType(MediaJob.class));
        }

        private <T extends DataContentBlock> MediaJobState<MediaResult<T>> fetch(
                MediaJob job, Class<T> blockType) {
            checkOpen();
            Objects.requireNonNull(job, "job");
            return read(
                    invoke("call_media_fetch", json(job)),
                    MAPPER.getTypeFactory()
                            .constructParametricType(MediaJobState.class, resultType(blockType)));
        }

        private <T extends DataContentBlock> MediaResult<T> wait(
                MediaJob job,
                @Nullable Duration timeout,
                Duration pollInterval,
                Class<T> blockType) {
            checkOpen();
            Objects.requireNonNull(job, "job");
            validateNonNegativeDuration(timeout, "timeout");
            validatePositiveDuration(
                    Objects.requireNonNull(pollInterval, "pollInterval"), "pollInterval");
            return read(
                    invoke(
                            "call_media_wait",
                            json(job),
                            toSeconds(timeout),
                            toSeconds(pollInterval)),
                    resultType(blockType));
        }

        private void cancel(MediaJob job) {
            checkOpen();
            Objects.requireNonNull(job, "job");
            outcome(invoke("call_media_cancel", json(job)));
        }
    }

    private abstract static class AsyncBinding<Req, T extends DataContentBlock>
            implements AsyncJobModel<Req, MediaResult<T>> {
        protected final Binding binding;
        private final Capability<Req, T> capability;

        private AsyncBinding(Binding binding, Capability<Req, T> capability) {
            this.binding = binding;
            this.capability = capability;
        }

        protected MediaResult<T> invokeGenerate(Req request, @Nullable Duration timeout) {
            return binding.generate(request, timeout, capability);
        }

        @Override
        public MediaJob submit(Req request) {
            return binding.submit(request, capability);
        }

        @Override
        public MediaJobState<MediaResult<T>> fetch(MediaJob job) {
            return binding.fetch(job, capability.blockType);
        }

        @Override
        public MediaResult<T> wait(
                MediaJob job, @Nullable Duration timeout, Duration pollInterval) {
            return binding.wait(job, timeout, pollInterval, capability.blockType);
        }

        @Override
        public void cancel(MediaJob job) {
            binding.cancel(job);
        }
    }

    private static final class AsyncSpeech extends AsyncBinding<SpeechSynthesisRequest, AudioBlock>
            implements SpeechSynthesisModel {
        private AsyncSpeech(Binding binding) {
            super(binding, SPEECH);
        }

        @Override
        public MediaResult<AudioBlock> synthesize(
                SpeechSynthesisRequest request, @Nullable Duration timeout) {
            return invokeGenerate(request, timeout);
        }
    }

    private static final class AsyncTranscription
            extends AsyncBinding<TranscriptionRequest, TextBlock> implements TranscriptionModel {
        private AsyncTranscription(Binding binding) {
            super(binding, TRANSCRIPTION);
        }

        @Override
        public MediaResult<TextBlock> transcribe(
                TranscriptionRequest request, @Nullable Duration timeout) {
            return invokeGenerate(request, timeout);
        }
    }

    private static final class AsyncImage extends AsyncBinding<ImageGenerationRequest, ImageBlock>
            implements ImageGenerationModel {
        private AsyncImage(Binding binding) {
            super(binding, IMAGE);
        }

        @Override
        public MediaResult<ImageBlock> generate(
                ImageGenerationRequest request, @Nullable Duration timeout) {
            return invokeGenerate(request, timeout);
        }
    }

    private static final class AsyncVideo extends AsyncBinding<VideoGenerationRequest, VideoBlock>
            implements VideoGenerationModel {
        private AsyncVideo(Binding binding) {
            super(binding, VIDEO);
        }

        @Override
        public MediaResult<VideoBlock> generate(
                VideoGenerationRequest request, @Nullable Duration timeout) {
            return invokeGenerate(request, timeout);
        }
    }

    private static JavaType resultType(Class<? extends DataContentBlock> blockType) {
        return MAPPER.getTypeFactory().constructParametricType(MediaResult.class, blockType);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Media request must contain JSON-compatible values", e);
        }
    }

    private static <T> T read(Object value, JavaType type) {
        String payload = outcome(value);
        if (payload == null) {
            throw new IllegalStateException("Missing MediaProvider invocation payload from Python");
        }
        try {
            return MAPPER.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid MediaProvider result from Python", e);
        }
    }

    /** Return the success payload. Cancel completes with a null result. */
    private static String outcome(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Invalid MediaProvider invocation outcome from Python");
        }
        Map<?, ?> outcome = (Map<?, ?>) value;
        if ("INTERRUPTED".equals(outcome.get("status"))
                || "CANCELLED".equals(outcome.get("status"))) {
            Object message = outcome.get("message");
            if (!(message instanceof String)) {
                throw new IllegalStateException(
                        "Invalid MediaProvider cancellation message from Python");
            }
            CancellationException cancellation = new CancellationException((String) message);
            if ("INTERRUPTED".equals(outcome.get("status"))) {
                cancellation.initCause(new InterruptedException(cancellation.getMessage()));
            }
            throw cancellation;
        }
        Object status = outcome.get("status");
        if ("SUCCESS".equals(status)) {
            Object result = outcome.get("result");
            if (result == null) {
                return null;
            }
            if (!(result instanceof String)) {
                throw new IllegalStateException(
                        "Invalid MediaProvider invocation payload from Python");
            }
            return (String) result;
        }
        if ("JOB_ERROR".equals(status) || "TIMEOUT".equals(status)) {
            throw jobException(outcome, "TIMEOUT".equals(status));
        }
        throw new IllegalStateException("Invalid MediaProvider invocation status from Python");
    }

    private static MediaJobException jobException(Map<?, ?> outcome, boolean timeout) {
        Object jobJson = outcome.get("job");
        Object statusValue = outcome.get("job_status");
        if (!(jobJson instanceof String) || !(statusValue instanceof String)) {
            throw new IllegalStateException("Invalid MediaProvider job error from Python");
        }
        final MediaJob job;
        try {
            job = MAPPER.readValue((String) jobJson, MediaJob.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid MediaProvider job from Python", e);
        }
        MediaJobStatus status = MediaJobStatus.fromValue((String) statusValue);
        String message =
                outcome.get("message") instanceof String ? (String) outcome.get("message") : null;
        if (timeout) {
            Object timeoutValue = outcome.get("timeout");
            double seconds =
                    timeoutValue instanceof Number ? ((Number) timeoutValue).doubleValue() : 0;
            return new MediaJobTimeoutException(job, status, seconds, message);
        }
        String code = outcome.get("code") instanceof String ? (String) outcome.get("code") : null;
        return new MediaJobException(job, status, code, message);
    }
}
