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
import org.apache.flink.agents.api.configuration.DurationUtils;
import org.apache.flink.agents.api.media.AsyncJobModel;
import org.apache.flink.agents.api.media.AsyncJobPolling;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobException;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaJobTimeoutException;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.SpeechSynthesisRequest;
import org.apache.flink.agents.api.media.TranscriptionRequest;
import org.apache.flink.agents.api.media.VideoGenerationRequest;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stateful Java-to-Python bridge backing {@link PythonMediaModelSetup} wrappers with lifecycle,
 * serialization, interruption handling, and outcome decoding.
 */
final class PythonMediaBridge {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ERROR_LABEL = "MediaModel";
    private static final String CALL_INTERRUPTED_MESSAGE = "Media model call interrupted";
    private static final String METHOD_OPEN = "open";
    private static final String METHOD_CLOSE = "close";

    static final String CALL_MEDIA_MODEL_GENERATE = "python_java_utils.call_media_model_generate";
    static final String CALL_MEDIA_MODEL_SUBMIT = "python_java_utils.call_media_model_submit";
    static final String CALL_MEDIA_MODEL_FETCH = "python_java_utils.call_media_model_fetch";
    static final String CALL_MEDIA_MODEL_CANCEL = "python_java_utils.call_media_model_cancel";

    private static final String KEY_STATUS = "status";
    private static final String KEY_RESULT = "result";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_JOB = "job";
    private static final String KEY_JOB_STATUS = "job_status";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_CODE = "code";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_IMAGES = "images";
    private static final String KEY_MEDIA = "media";

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_JOB_ERROR = "JOB_ERROR";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final String STATUS_INTERRUPTED = "INTERRUPTED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    static final Capability<SpeechSynthesisRequest, AudioBlock> SPEECH =
            new Capability<>(
                    AudioBlock.class,
                    SpeechSynthesisRequest::getText,
                    SpeechSynthesisRequest::getModelParameters);
    static final Capability<TranscriptionRequest, TextBlock> TRANSCRIPTION =
            new Capability<>(
                    TextBlock.class,
                    TranscriptionRequest::getAudio,
                    TranscriptionRequest::getModelParameters);
    static final Capability<ImageGenerationRequest, ImageBlock> IMAGE =
            new Capability<>(
                    ImageBlock.class,
                    req -> Map.of(KEY_PROMPT, req.getPrompt(), KEY_IMAGES, req.getImages()),
                    ImageGenerationRequest::getModelParameters);
    static final Capability<VideoGenerationRequest, VideoBlock> VIDEO =
            new Capability<>(
                    VideoBlock.class,
                    req -> Map.of(KEY_PROMPT, req.getPrompt(), KEY_MEDIA, req.getMedia()),
                    VideoGenerationRequest::getModelParameters);

    private final PythonResourceAdapter adapter;
    private final PyObject resource;
    private final String task;
    private final ResourceDescriptor descriptor;
    @Nullable private final ResourceContext resourceContext;
    private volatile boolean closed;

    PythonMediaBridge(
            PythonResourceAdapter adapter,
            PyObject resource,
            String task,
            ResourceDescriptor descriptor,
            @Nullable ResourceContext resourceContext) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.task = Objects.requireNonNull(task, "task");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.resourceContext = resourceContext;
    }

    static final class Capability<Req, T extends DataContentBlock> {
        final Class<T> blockType;
        final Function<Req, Object> payload;
        final Function<Req, Map<String, Object>> modelParameters;

        private Capability(
                Class<T> blockType,
                Function<Req, Object> payload,
                Function<Req, Map<String, Object>> modelParameters) {
            this.blockType = blockType;
            this.payload = payload;
            this.modelParameters = modelParameters;
        }
    }

    static @Nullable Double toSeconds(@Nullable Duration duration) {
        return duration == null
                ? null
                : duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Media request must contain JSON-compatible values", e);
        }
    }

    void open() {
        adapter.callMethod(resource, METHOD_OPEN, Map.of());
    }

    void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try (resource) {
            adapter.callMethod(resource, METHOD_CLOSE, Map.of());
        }
    }

    Object getPythonResource() {
        return resource;
    }

    PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    ResourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Nullable
    ResourceContext getResourceContext() {
        return resourceContext;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Media model is closed");
        }
    }

    <Req, T extends DataContentBlock> MediaResult<T> generate(
            Req request, @Nullable Duration timeout, Capability<Req, T> capability) {
        checkOpen();
        Objects.requireNonNull(request, "request");
        if (timeout != null) {
            AsyncJobPolling.validatePositiveDuration(timeout, "timeout");
        }
        String inputJson = json(capability.payload.apply(request));
        Double timeoutSeconds = toSeconds(timeout);
        String paramsJson = json(capability.modelParameters.apply(request));
        Object value =
                invokeInterruptibly(
                        () ->
                                adapter.invoke(
                                        CALL_MEDIA_MODEL_GENERATE,
                                        resource,
                                        task,
                                        inputJson,
                                        timeoutSeconds,
                                        paramsJson));
        return read(value, resultType(capability.blockType));
    }

    <Req, T extends DataContentBlock> MediaResult<T> submitAndWait(
            AsyncJobModel<Req, MediaResult<T>> asyncModel,
            Req request,
            @Nullable Duration timeout,
            Capability<Req, T> capability) {
        checkOpen();
        Objects.requireNonNull(request, "request");
        if (timeout != null) {
            AsyncJobPolling.validatePositiveDuration(timeout, "timeout");
        }
        checkNotInterrupted();
        Duration waitBudget = timeout == null ? AsyncJobModel.DEFAULT_WAIT_TIMEOUT : timeout;
        MediaJob job = submit(request, capability);
        return asyncModel.wait(job, waitBudget, AsyncJobModel.DEFAULT_POLL_INTERVAL);
    }

    <Req> MediaJob submit(Req request, Capability<Req, ?> capability) {
        checkOpen();
        Objects.requireNonNull(request, "request");
        String inputJson = json(capability.payload.apply(request));
        String paramsJson = json(capability.modelParameters.apply(request));
        Object value =
                invokeInterruptibly(
                        () ->
                                adapter.invoke(
                                        CALL_MEDIA_MODEL_SUBMIT,
                                        resource,
                                        task,
                                        inputJson,
                                        paramsJson));
        return read(value, MAPPER.constructType(MediaJob.class));
    }

    <T extends DataContentBlock> MediaJobState<MediaResult<T>> fetch(
            MediaJob job, Class<T> blockType) {
        checkOpen();
        Objects.requireNonNull(job, "job");
        String jobJson = json(job);
        Object value =
                invokeInterruptibly(
                        () -> adapter.invoke(CALL_MEDIA_MODEL_FETCH, resource, jobJson));
        return read(
                value,
                MAPPER.getTypeFactory()
                        .constructParametricType(MediaJobState.class, resultType(blockType)));
    }

    void cancel(MediaJob job) {
        checkOpen();
        Objects.requireNonNull(job, "job");
        // Intentionally bypass invokeInterruptibly so cleanup cancellation still runs when
        // interrupted.
        outcome(adapter.invoke(CALL_MEDIA_MODEL_CANCEL, resource, json(job)));
    }

    <Res extends MediaResult<?>> Res waitJob(
            Function<MediaJob, MediaJobState<Res>> fetcher,
            MediaJob job,
            @Nullable Duration timeout,
            Duration pollInterval) {
        return AsyncJobPolling.wait(
                fetcher, job, timeout, pollInterval, UncapturableException::isUncapturable);
    }

    private static Object invokeInterruptibly(Supplier<Object> call) {
        checkNotInterrupted();
        final Object result;
        try {
            result = call.get();
        } catch (RuntimeException failure) {
            UncapturableException.rethrowIfPresent(failure);
            if (CancellationUtils.isCancellation(failure)) {
                if (CancellationUtils.isInterruption(failure)) {
                    Thread.currentThread().interrupt();
                }
                throw failure;
            }
            if (Thread.currentThread().isInterrupted()) {
                CancellationException cancellation =
                        interruptedCancellation(new InterruptedException(CALL_INTERRUPTED_MESSAGE));
                cancellation.addSuppressed(failure);
                throw cancellation;
            }
            throw failure;
        }
        checkNotInterrupted();
        return result;
    }

    private static void checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw interruptedCancellation(new InterruptedException(CALL_INTERRUPTED_MESSAGE));
        }
    }

    private static CancellationException interruptedCancellation(InterruptedException cause) {
        String message = cause.getMessage();
        CancellationException cancellation =
                new CancellationException(
                        message == null || message.isEmpty() ? CALL_INTERRUPTED_MESSAGE : message);
        cancellation.initCause(cause);
        return cancellation;
    }

    private static JavaType resultType(Class<? extends DataContentBlock> blockType) {
        return MAPPER.getTypeFactory().constructParametricType(MediaResult.class, blockType);
    }

    private static <T> T read(Object value, JavaType type) {
        String payload = outcome(value);
        if (payload == null) {
            throw new IllegalStateException(
                    "Missing " + ERROR_LABEL + " invocation payload from Python");
        }
        try {
            return MAPPER.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid " + ERROR_LABEL + " result from Python", e);
        }
    }

    /** Return the success payload. Cancel completes with a null result. */
    @Nullable
    private static String outcome(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException(
                    "Invalid " + ERROR_LABEL + " invocation outcome from Python");
        }
        Map<?, ?> outcome = (Map<?, ?>) value;
        Object status = outcome.get(KEY_STATUS);
        if (STATUS_INTERRUPTED.equals(status) || STATUS_CANCELLED.equals(status)) {
            Object message = outcome.get(KEY_MESSAGE);
            if (!(message instanceof String)) {
                throw new IllegalStateException(
                        "Invalid " + ERROR_LABEL + " cancellation message from Python");
            }
            CancellationException cancellation = new CancellationException((String) message);
            if (STATUS_INTERRUPTED.equals(status)) {
                cancellation.initCause(new InterruptedException(cancellation.getMessage()));
            }
            throw cancellation;
        }
        if (STATUS_SUCCESS.equals(status)) {
            Object result = outcome.get(KEY_RESULT);
            if (result == null) {
                return null;
            }
            if (!(result instanceof String)) {
                throw new IllegalStateException(
                        "Invalid " + ERROR_LABEL + " invocation payload from Python");
            }
            return (String) result;
        }
        if (STATUS_JOB_ERROR.equals(status) || STATUS_TIMEOUT.equals(status)) {
            throw jobException(outcome, STATUS_TIMEOUT.equals(status));
        }
        throw new IllegalStateException(
                "Invalid " + ERROR_LABEL + " invocation status from Python");
    }

    private static MediaJobException jobException(Map<?, ?> outcome, boolean timeout) {
        Object jobJson = outcome.get(KEY_JOB);
        Object statusValue = outcome.get(KEY_JOB_STATUS);
        if (!(jobJson instanceof String) || !(statusValue instanceof String)) {
            throw new IllegalStateException("Invalid " + ERROR_LABEL + " job error from Python");
        }
        final MediaJob job;
        try {
            job = MAPPER.readValue((String) jobJson, MediaJob.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid " + ERROR_LABEL + " job from Python", e);
        }
        final MediaJobStatus status;
        try {
            status = MediaJobStatus.fromValue((String) statusValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid " + ERROR_LABEL + " job status from Python", e);
        }
        String message =
                outcome.get(KEY_MESSAGE) instanceof String
                        ? (String) outcome.get(KEY_MESSAGE)
                        : null;
        if (timeout) {
            Duration timeoutDuration = timeoutFromSeconds(outcome.get(KEY_TIMEOUT));
            return new MediaJobTimeoutException(job, status, timeoutDuration, message);
        }
        String code =
                outcome.get(KEY_CODE) instanceof String ? (String) outcome.get(KEY_CODE) : null;
        return new MediaJobException(job, status, code, message);
    }

    private static Duration timeoutFromSeconds(@Nullable Object timeoutValue) {
        if (!(timeoutValue instanceof Number)) {
            throw new IllegalStateException("Invalid " + ERROR_LABEL + " timeout from Python");
        }
        double seconds = ((Number) timeoutValue).doubleValue();
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds >= Long.MAX_VALUE) {
            throw new IllegalStateException("Invalid " + ERROR_LABEL + " timeout from Python");
        }
        long wholeSeconds = (long) seconds;
        long nanos = Math.round((seconds - wholeSeconds) * 1_000_000_000.0);
        Duration duration = Duration.ofSeconds(wholeSeconds, nanos);
        if (!DurationUtils.isPositive(duration)) {
            throw new IllegalStateException("Invalid " + ERROR_LABEL + " timeout from Python");
        }
        return duration;
    }
}
