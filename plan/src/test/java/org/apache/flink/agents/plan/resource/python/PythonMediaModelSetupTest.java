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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.flink.agents.api.media.MediaJobException;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaJobTimeoutException;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.SpeechSynthesisRequest;
import org.apache.flink.agents.api.media.TranscriptionRequest;
import org.apache.flink.agents.api.media.VideoGenerationRequest;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PythonMediaModelSetup} and {@link PythonResourceProvider} media setups. */
class PythonMediaModelSetupTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaJob JOB = new MediaJob("job-1", 1, null);
    private static final String JOB_JSON = "{\"job_id\":\"job-1\",\"submitted_at_ms\":1}";

    @ParameterizedTest
    @CsvSource({
        "speech_synthesis, false",
        "speech_synthesis, true",
        "transcription, false",
        "transcription, true",
        "image_generation, false",
        "image_generation, true",
        "video_generation, false",
        "video_generation, true"
    })
    void createExposesExactCapabilityAsyncInterfacesModelAndVoice(String task, boolean async) {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyResource = mock(PyObject.class);
        Map<String, Object> inspection =
                "speech_synthesis".equals(task)
                        ? Map.of(
                                "task",
                                task,
                                "async",
                                async,
                                "connection",
                                "dashscope",
                                "model",
                                "tts-m",
                                "voice",
                                "Cherry")
                        : Map.of(
                                "task",
                                task,
                                "async",
                                async,
                                "connection",
                                "dashscope",
                                "model",
                                "media-m");
        when(adapter.invoke("python_java_utils.inspect_media_model", pyResource))
                .thenReturn(inspection);

        BaseMediaModelSetup setup =
                PythonMediaModelSetup.create(
                        adapter, pyResource, new ResourceDescriptor("py.Setup", Map.of()), null);

        assertThat(setup.getResourceType()).isEqualTo(ResourceType.MEDIA_MODEL);
        assertThat(setup.getConnectionName()).isEqualTo("dashscope");
        assertThat(setup instanceof AsyncJobModel).isEqualTo(async);
        assertThat(setup instanceof BaseSpeechSynthesisModelSetup)
                .isEqualTo("speech_synthesis".equals(task));
        assertThat(setup instanceof BaseTranscriptionModelSetup)
                .isEqualTo("transcription".equals(task));
        assertThat(setup instanceof BaseImageGenerationModelSetup)
                .isEqualTo("image_generation".equals(task));
        assertThat(setup instanceof BaseVideoGenerationModelSetup)
                .isEqualTo("video_generation".equals(task));
        if ("speech_synthesis".equals(task)) {
            assertThat(setup.getModel()).isEqualTo("tts-m");
            assertThat(((BaseSpeechSynthesisModelSetup) setup).getVoice()).isEqualTo("Cherry");
        } else {
            assertThat(setup.getModel()).isEqualTo("media-m");
        }
    }

    @Test
    void createRejectsInvalidInspectionResult() {
        for (Map<String, Object> invalid :
                List.<Map<String, Object>>of(
                        Map.of(
                                "task",
                                "unknown_task",
                                "async",
                                false,
                                "connection",
                                "dashscope",
                                "model",
                                "m"),
                        Map.of("task", "image_generation", "async", false, "connection", "c"),
                        Map.of("task", "image_generation", "async", false, "model", "m"),
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                false,
                                "connection",
                                "c",
                                "model",
                                "   "),
                        Map.of(
                                "task",
                                "speech_synthesis",
                                "async",
                                false,
                                "connection",
                                "c",
                                "model",
                                "m",
                                "voice",
                                "   "))) {
            PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
            PyObject pyResource = mock(PyObject.class);
            when(adapter.invoke("python_java_utils.inspect_media_model", pyResource))
                    .thenReturn(invalid);

            assertThatThrownBy(
                            () ->
                                    PythonMediaModelSetup.create(
                                            adapter,
                                            pyResource,
                                            new ResourceDescriptor("py.Setup", Map.of()),
                                            null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid MediaModel inspection result from Python");
        }
    }

    @Test
    void syncSpeechInvokesBridgeLifecycleAndRejectsClosedUse() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyResource = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyResource))
                .thenReturn(
                        Map.of(
                                "task",
                                "speech_synthesis",
                                "async",
                                false,
                                "connection",
                                "dashscope",
                                "model",
                                "tts"));
        when(adapter.invoke(
                        eq("python_java_utils.call_media_model_generate"),
                        eq(pyResource),
                        eq("speech_synthesis"),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                "{\"content\":[{\"type\":\"audio\",\"media_type\":\"audio/wav\",\"source\":{\"type\":\"url\",\"url\":\"https://example.test/a.wav\"}}]}"));

        BaseMediaModelSetup setup =
                PythonMediaModelSetup.create(
                        adapter, pyResource, new ResourceDescriptor("py.Setup", Map.of()), null);
        setup.open();
        verify(adapter).callMethod(pyResource, "open", Map.of());

        MediaResult<AudioBlock> result =
                ((BaseSpeechSynthesisModelSetup) setup)
                        .synthesize(SpeechSynthesisRequest.of("hello"));
        assertThat(result.getContent().get(0).getSource()).isNotNull();

        setup.close();
        setup.close();
        verify(adapter).callMethod(pyResource, "close", Map.of());
        verify(pyResource).close();

        assertThatThrownBy(
                        () ->
                                ((BaseSpeechSynthesisModelSetup) setup)
                                        .synthesize(SpeechSynthesisRequest.of("after close")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Media model is closed");
    }

    @Test
    void openCallsPythonOpenDirectlyAndUsesInspectedMetadata() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pySetup = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pySetup))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "dashscope",
                                "model",
                                "qwen-image-3.0"));

        ResourceContext okCtx = mock(ResourceContext.class);

        ResourceDescriptor descriptor =
                PythonResourceDescriptor.Builder.newBuilder(
                                "example.media.ImageGenerationModelSetup")
                        .addInitialArgument("connection", "dashscope")
                        .addInitialArgument("model", "qwen-image-3.0")
                        .build();

        BaseMediaModelSetup setup =
                PythonMediaModelSetup.create(adapter, pySetup, descriptor, okCtx);
        assertThat(setup.getConnectionName()).isEqualTo("dashscope");
        setup.open();
        verify(adapter).callMethod(pySetup, "open", Map.of());
        verifyNoMoreInteractions(okCtx);

        // Inspected metadata overrides omitted or stale descriptor arguments.
        PyObject defaultedPySetup = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", defaultedPySetup))
                .thenReturn(
                        Map.of(
                                "task",
                                "speech_synthesis",
                                "async",
                                false,
                                "model",
                                "cosyvoice-v3-plus",
                                "connection",
                                "live-conn"));
        BaseSpeechSynthesisModelSetup defaultedSetup =
                (BaseSpeechSynthesisModelSetup)
                        PythonMediaModelSetup.create(
                                adapter,
                                defaultedPySetup,
                                PythonResourceDescriptor.Builder.newBuilder(
                                                "example.media.SpeechSynthesisModelSetup")
                                        .addInitialArgument("connection", "stale-conn")
                                        .addInitialArgument("model", "stale-model")
                                        .addInitialArgument("voice", "stale-voice")
                                        .build(),
                                okCtx);
        assertThat(defaultedSetup.getConnectionName()).isEqualTo("live-conn");
        assertThat(defaultedSetup.getModel()).isEqualTo("cosyvoice-v3-plus");
        assertThat(defaultedSetup.getVoice()).isNull();
        defaultedSetup.open();
        verify(adapter).callMethod(defaultedPySetup, "open", Map.of());
        verifyNoMoreInteractions(okCtx);

        // Rejects blank inspected connection string.
        PyObject blankConnPySetup = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", blankConnPySetup))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "   ",
                                "model",
                                "qwen-image-3.0"));
        assertThatThrownBy(
                        () ->
                                PythonMediaModelSetup.create(
                                        adapter, blankConnPySetup, descriptor, okCtx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid MediaModel inspection result from Python");
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncModelSupportsPollingCancellationAndCancelWithoutInterruptCheck() throws Exception {
        String jobJson = PythonMediaBridge.json(JOB);
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyResource = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyResource))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke(
                        eq("python_java_utils.call_media_model_submit"),
                        eq(pyResource),
                        eq("image_generation"),
                        any(),
                        any()))
                .thenReturn(Map.of("status", "SUCCESS", "result", jobJson));
        CountDownLatch sleeping = new CountDownLatch(1);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        when(adapter.invoke("python_java_utils.call_media_model_fetch", pyResource, jobJson))
                .thenAnswer(
                        invocation -> {
                            if (Thread.currentThread() == waiterThread.get()) {
                                sleeping.countDown();
                            }
                            return Map.of(
                                    "status",
                                    "SUCCESS",
                                    "result",
                                    "{\"job\":" + jobJson + ",\"status\":\"running\"}");
                        });
        when(adapter.invoke("python_java_utils.call_media_model_cancel", pyResource, jobJson))
                .thenReturn(Map.of("status", "SUCCESS"));

        BaseMediaModelSetup setup =
                PythonMediaModelSetup.create(
                        adapter, pyResource, new ResourceDescriptor("py.Setup", Map.of()), null);
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) setup;
        MediaJob job = async.submit(ImageGenerationRequest.of("a cat"));
        MediaJobState<MediaResult<ImageBlock>> state = async.fetch(job);
        assertThat(state.getStatus()).isEqualTo(MediaJobStatus.RUNNING);
        assertThat(sleeping.getCount()).isEqualTo(1L);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread waiter =
                new Thread(
                        () -> {
                            try {
                                async.wait(job, Duration.ofSeconds(10), Duration.ofSeconds(5));
                            } catch (Throwable t) {
                                failure.set(t);
                                interruptRestored.set(Thread.interrupted());
                            }
                        });
        waiterThread.set(waiter);
        waiter.start();
        assertThat(sleeping.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(30);
        waiter.interrupt();
        waiter.join(2000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(CancellationException.class);
        assertThat(CancellationUtils.isInterruption(failure.get())).isTrue();
        assertThat(interruptRestored.get()).isTrue();

        // Cancellation cleanup runs even if the thread interrupt flag is set.
        Thread.currentThread().interrupt();
        try {
            async.cancel(job);
            verify(adapter)
                    .invoke("python_java_utils.call_media_model_cancel", pyResource, jobJson);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptDuringTerminalOrThrowingFetchAndUncapturableHandling() throws Exception {
        String jobJson = PythonMediaBridge.json(JOB);
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyResource = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyResource))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        RuntimeException transientFailure = new RuntimeException("transient");
        UncapturableException fault =
                UncapturableException.wrap(new IllegalStateException("fatal"));
        when(adapter.invoke("python_java_utils.call_media_model_fetch", pyResource, jobJson))
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            return Map.of(
                                    "status",
                                    "SUCCESS",
                                    "result",
                                    "{\"job\":"
                                            + jobJson
                                            + ",\"status\":\"failed\",\"error_code\":\"E\",\"error_message\":\"bad\"}");
                        })
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            throw transientFailure;
                        })
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            throw fault;
                        });

        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> async =
                (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>)
                        PythonMediaModelSetup.create(
                                adapter,
                                pyResource,
                                new ResourceDescriptor("py.Setup", Map.of()),
                                null);
        try {
            Throwable failure = catchThrowable(() -> async.wait(JOB));
            assertThat(failure).isInstanceOf(CancellationException.class);
            assertThat(CancellationUtils.isInterruption(failure)).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        try {
            Throwable failure = catchThrowable(() -> async.wait(JOB));
            assertThat(failure).isInstanceOf(CancellationException.class);
            assertThat(failure.getSuppressed()).containsExactly(transientFailure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        try {
            assertThatThrownBy(() -> async.wait(JOB)).isSameAs(fault);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void transcriptionAndVideoWrappersDelegateCleanly() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyTranscription = mock(PyObject.class);
        PyObject pyVideo = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyTranscription))
                .thenReturn(
                        Map.of(
                                "task",
                                "transcription",
                                "async",
                                false,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pyVideo))
                .thenReturn(
                        Map.of(
                                "task",
                                "video_generation",
                                "async",
                                false,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke(
                        eq("python_java_utils.call_media_model_generate"),
                        eq(pyTranscription),
                        eq("transcription"),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));
        when(adapter.invoke(
                        eq("python_java_utils.call_media_model_generate"),
                        eq(pyVideo),
                        eq("video_generation"),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                "{\"content\":[{\"type\":\"video\",\"media_type\":\"video/mp4\",\"source\":{\"type\":\"url\",\"url\":\"https://example.test/v.mp4\"}}]}"));

        BaseTranscriptionModelSetup transcription =
                (BaseTranscriptionModelSetup)
                        PythonMediaModelSetup.create(
                                adapter,
                                pyTranscription,
                                new ResourceDescriptor("py.Setup", Map.of()),
                                null);
        assertThat(
                        ((TextBlock)
                                        transcription
                                                .transcribe(
                                                        TranscriptionRequest.of(
                                                                AudioBlock.fromUrl(
                                                                        "audio/mp3",
                                                                        "https://example.test/in.mp3")))
                                                .getContent()
                                                .get(0))
                                .getText())
                .isEqualTo("hello");

        BaseVideoGenerationModelSetup video =
                (BaseVideoGenerationModelSetup)
                        PythonMediaModelSetup.create(
                                adapter,
                                pyVideo,
                                new ResourceDescriptor("py.Setup", Map.of()),
                                null);
        assertThat(video.generate(VideoGenerationRequest.of("sunset")).getContent())
                .hasExactlyElementsOfTypes(VideoBlock.class);
    }

    @Test
    void pythonResourceProviderCreatesSetupBridgeWithoutEarlyContextLookupAndClosesPyObjectOnError()
            throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        ResourceContext ctx = mock(ResourceContext.class);
        PyObject pySetup = mock(PyObject.class);
        when(adapter.initPythonResource(
                        eq("example.media"), eq("ImageGenerationModelSetup"), anyMap(), eq(ctx)))
                .thenReturn(pySetup);
        when(adapter.invoke("python_java_utils.inspect_media_model", pySetup))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "dashscope",
                                "model",
                                "qwen-image-3.0"));

        ResourceDescriptor setupDesc =
                PythonResourceDescriptor.Builder.newBuilder(
                                "example.media.ImageGenerationModelSetup")
                        .addInitialArgument("connection", "dashscope")
                        .addInitialArgument("model", "qwen-image-3.0")
                        .build();
        PythonResourceProvider provider =
                new PythonResourceProvider("poster", ResourceType.MEDIA_MODEL, setupDesc);
        provider.setPythonResourceAdapter(adapter);
        Resource provided = provider.provide(ctx);
        assertThat(provided).isInstanceOf(BaseImageGenerationModelSetup.class);
        assertThat(provided).isInstanceOf(AsyncJobModel.class);
        assertThat(provided.getResourceContext()).isSameAs(ctx);
        verifyNoMoreInteractions(ctx);

        // Closes PyObject when inspection fails.
        ResourceContext badCtx = mock(ResourceContext.class);
        PyObject badPyResource = mock(PyObject.class);
        when(adapter.initPythonResource(
                        eq("custom.media"), eq("CustomSetup"), anyMap(), eq(badCtx)))
                .thenReturn(badPyResource);
        when(adapter.invoke("python_java_utils.inspect_media_model", badPyResource))
                .thenReturn(Map.of("task", "unknown_task", "async", false));
        PythonResourceProvider badProvider =
                new PythonResourceProvider(
                        "custom",
                        ResourceType.MEDIA_MODEL,
                        new PythonResourceDescriptor("custom.media", "CustomSetup", Map.of()));
        badProvider.setPythonResourceAdapter(adapter);

        assertThatThrownBy(() -> badProvider.provide(badCtx))
                .isInstanceOf(IllegalStateException.class);
        verify(badCtx, never()).getResource(any(), any());
        verify(badPyResource).close();
        verifyNoMoreInteractions(badCtx);
    }

    @ParameterizedTest
    @CsvSource({
        "generate,INTERRUPTED",
        "submit,INTERRUPTED",
        "fetch,INTERRUPTED",
        "wait,INTERRUPTED",
        "cancel,INTERRUPTED",
        "generate,CANCELLED",
        "submit,CANCELLED",
        "fetch,CANCELLED",
        "wait,CANCELLED",
        "cancel,CANCELLED"
    })
    void preservesCancellation(String operation, String status) {
        BaseVideoGenerationModelSetup model =
                setupWithOutcome(
                        "video_generation",
                        true,
                        Map.of("status", status, "message", "operation stopped"));
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> async = asAsync(model);
        Throwable failure =
                catchThrowable(
                        () -> {
                            switch (operation) {
                                case "generate":
                                    model.generate("A notebook.");
                                    break;
                                case "submit":
                                    async.submit(VideoGenerationRequest.of("A notebook."));
                                    break;
                                case "fetch":
                                    async.fetch(JOB);
                                    break;
                                case "wait":
                                    async.wait(JOB);
                                    break;
                                case "cancel":
                                    async.cancel(JOB);
                                    break;
                                default:
                                    throw new AssertionError(operation);
                            }
                        });
        assertThat(failure)
                .isInstanceOf(CancellationException.class)
                .hasMessage("operation stopped");
        assertThat(CancellationUtils.isCancellation(failure)).isTrue();
        assertThat(CancellationUtils.isInterruption(failure))
                .isEqualTo("INTERRUPTED".equals(status));
    }

    @Test
    void jobErrorAndTimeoutRemainBusinessFailures() throws Exception {
        BaseVideoGenerationModelSetup errorModel =
                setupWithOutcome(
                        "video_generation",
                        true,
                        Map.of(
                                "status", "JOB_ERROR",
                                "job_status", "failed",
                                "code", "BadRequest",
                                "message", "invalid prompt",
                                "job", JOB_JSON));
        Throwable error = catchThrowable(() -> errorModel.generate("p"));
        assertThat(error).isInstanceOf(MediaJobException.class);
        assertThat(((MediaJobException) error).getStatus()).isEqualTo(MediaJobStatus.FAILED);
        assertThat(((MediaJobException) error).getCode()).isEqualTo("BadRequest");
        assertThat(CancellationUtils.isCancellation(error)).isFalse();

        String jobJson = PythonMediaBridge.json(JOB);

        // Terminal states and result-less SUCCEEDED states observed during Java polling.
        for (MediaJobStatus terminal :
                List.of(
                        MediaJobStatus.FAILED,
                        MediaJobStatus.CANCELED,
                        MediaJobStatus.UNKNOWN,
                        MediaJobStatus.SUCCEEDED)) {
            PythonResourceAdapter terminalAdapter = mock(PythonResourceAdapter.class);
            PyObject pyModel = mockVideoPyModel(terminalAdapter, true);
            String state =
                    terminal == MediaJobStatus.FAILED
                            ? stateJson(terminal, null, "BadInput", "rejected")
                            : stateJson(terminal, null, null, null);
            when(terminalAdapter.invoke(
                            "python_java_utils.call_media_model_fetch", pyModel, jobJson))
                    .thenReturn(Map.of("status", "SUCCESS", "result", state));
            AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> async =
                    asAsync(setup(terminalAdapter, pyModel));
            Throwable stateError = catchThrowable(() -> async.wait(JOB));
            assertThat(stateError).isInstanceOf(MediaJobException.class);
            assertThat(stateError).isNotInstanceOf(MediaJobTimeoutException.class);
            assertThat(((MediaJobException) stateError).getStatus()).isEqualTo(terminal);
            assertThat(CancellationUtils.isCancellation(stateError)).isFalse();
        }

        // Python-returned TIMEOUT outcomes on single bridge calls remain validated cleanly.
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> async =
                asAsync(
                        setupWithOutcome(
                                "video_generation",
                                true,
                                Map.of(
                                        "status", "TIMEOUT",
                                        "job_status", "running",
                                        "timeout", 12.5,
                                        "message", "timed out waiting for job",
                                        "job", JOB_JSON)));
        Throwable timeout = catchThrowable(() -> async.fetch(JOB));
        assertThat(timeout).isInstanceOf(MediaJobTimeoutException.class);
        MediaJobTimeoutException timeoutError = (MediaJobTimeoutException) timeout;
        assertThat(timeoutError.getStatus()).isEqualTo(MediaJobStatus.RUNNING);
        assertThat(timeoutError.getTimeout()).isEqualTo(Duration.ofMillis(12500));
        assertThat(timeoutError.getJob().getJobId()).isEqualTo("job-1");
        assertThat(CancellationUtils.isCancellation(timeout)).isFalse();

        for (Object invalidTimeout :
                new Object[] {
                    null, "12.5", 0.0, -1.0, 1e-12, Double.NaN, Double.POSITIVE_INFINITY, 1e30,
                }) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "TIMEOUT");
            payload.put("job_status", "running");
            payload.put("timeout", invalidTimeout);
            payload.put("message", "timed out");
            payload.put("job", JOB_JSON);
            AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> badModel =
                    asAsync(setupWithOutcome("video_generation", true, payload));
            assertThatThrownBy(() -> badModel.fetch(JOB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid MediaModel timeout from Python");
        }

        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> badStatusModel =
                asAsync(
                        setupWithOutcome(
                                "video_generation",
                                true,
                                Map.of(
                                        "status",
                                        "JOB_ERROR",
                                        "job_status",
                                        "bogus_status",
                                        "message",
                                        "bad",
                                        "job",
                                        JOB_JSON)));
        assertThatThrownBy(() -> badStatusModel.fetch(JOB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid MediaModel job status from Python");
    }

    @Test
    void requestsAllowNullOptionalInputs() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyImage = mock(PyObject.class);
        PyObject pyVideo = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyImage))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pyVideo))
                .thenReturn(
                        Map.of(
                                "task",
                                "video_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        MediaJob imageJob = new MediaJob("job-img", 1, null);
        MediaJob videoJob = new MediaJob("job-vid", 1, null);
        String imageJobJson = MAPPER.writeValueAsString(imageJob);
        String videoJobJson = MAPPER.writeValueAsString(videoJob);
        when(adapter.invoke(
                        eq("python_java_utils.call_media_model_submit"),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        Map.of("status", "SUCCESS", "result", imageJobJson),
                        Map.of("status", "SUCCESS", "result", videoJobJson),
                        Map.of("status", "SUCCESS", "result", JOB_JSON));
        when(adapter.invoke("python_java_utils.call_media_model_fetch", pyImage, imageJobJson))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                stateJson(
                                        imageJob,
                                        MediaJobStatus.SUCCEEDED,
                                        "{\"content\":[]}",
                                        null,
                                        null)));
        when(adapter.invoke("python_java_utils.call_media_model_fetch", pyVideo, videoJobJson))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                stateJson(
                                        videoJob,
                                        MediaJobStatus.SUCCEEDED,
                                        "{\"content\":[]}",
                                        null,
                                        null)));

        BaseImageGenerationModelSetup image =
                (BaseImageGenerationModelSetup) setup(adapter, pyImage);
        BaseVideoGenerationModelSetup video =
                (BaseVideoGenerationModelSetup) setup(adapter, pyVideo);
        ImageBlock frame = ImageBlock.fromUrl("image/png", "https://example.test/frame.png");

        image.generate(
                ImageGenerationRequest.builder("A notebook.")
                        .images(null)
                        .modelParameters(null)
                        .build(),
                null);
        video.generate(VideoGenerationRequest.builder(null).media(List.of(frame)).build(), null);
        this.<VideoGenerationRequest, VideoBlock>asAsync(video)
                .submit(VideoGenerationRequest.builder().media(null).modelParameters(null).build());

        ArgumentCaptor<String> submitInputs = ArgumentCaptor.forClass(String.class);
        verify(adapter, times(3))
                .invoke(
                        eq("python_java_utils.call_media_model_submit"),
                        any(),
                        any(),
                        submitInputs.capture(),
                        eq("{}"));
        assertThat(MAPPER.readTree(submitInputs.getAllValues().get(0)))
                .isEqualTo(MAPPER.readTree("{\"prompt\":\"A notebook.\",\"images\":[]}"));
        assertThat(MAPPER.readTree(submitInputs.getAllValues().get(1)).path("prompt").asText())
                .isEmpty();
        assertThat(MAPPER.readTree(submitInputs.getAllValues().get(1)).path("media"))
                .isEqualTo(MAPPER.valueToTree(List.of(frame)));
        assertThat(MAPPER.readTree(submitInputs.getAllValues().get(2)))
                .isEqualTo(MAPPER.readTree("{\"prompt\":\"\",\"media\":[]}"));
    }

    @Test
    void rejectsNullRequiredInputsAndNonPositiveDurationsBeforeCallingPython() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pySpeech = mock(PyObject.class);
        PyObject pyTranscription = mock(PyObject.class);
        PyObject pyImage = mock(PyObject.class);
        PyObject pyVideo = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pySpeech))
                .thenReturn(
                        Map.of(
                                "task",
                                "speech_synthesis",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pyTranscription))
                .thenReturn(
                        Map.of(
                                "task",
                                "transcription",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pyImage))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pyVideo))
                .thenReturn(
                        Map.of(
                                "task",
                                "video_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));

        BaseSpeechSynthesisModelSetup speech =
                (BaseSpeechSynthesisModelSetup) setup(adapter, pySpeech);
        BaseTranscriptionModelSetup transcription =
                (BaseTranscriptionModelSetup) setup(adapter, pyTranscription);
        BaseImageGenerationModelSetup image =
                (BaseImageGenerationModelSetup) setup(adapter, pyImage);
        BaseVideoGenerationModelSetup video =
                (BaseVideoGenerationModelSetup) setup(adapter, pyVideo);
        AsyncJobModel<SpeechSynthesisRequest, MediaResult<AudioBlock>> asyncSpeech =
                asAsync(speech);
        AsyncJobModel<TranscriptionRequest, MediaResult<TextBlock>> asyncTranscription =
                asAsync(transcription);
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> asyncImage = asAsync(image);
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> asyncVideo = asAsync(video);

        assertNullRejected(() -> speech.synthesize((SpeechSynthesisRequest) null), "request");
        assertNullRejected(() -> asyncSpeech.submit(null), "request");
        assertNullRejected(() -> transcription.transcribe((TranscriptionRequest) null), "request");
        assertNullRejected(() -> asyncTranscription.submit(null), "request");
        assertNullRejected(() -> image.generate((ImageGenerationRequest) null), "request");
        assertNullRejected(() -> asyncImage.submit(null), "request");
        assertNullRejected(() -> video.generate((VideoGenerationRequest) null), "request");
        assertNullRejected(() -> asyncVideo.submit(null), "request");
        assertNullRejected(() -> asyncImage.fetch(null), "job");
        assertNullRejected(() -> asyncImage.wait(null), "job");
        assertNullRejected(
                () -> asyncImage.wait(JOB, Duration.ofSeconds(10), null), "pollInterval");
        assertNullRejected(() -> asyncImage.cancel(null), "job");

        assertInvalidDuration(
                () -> speech.synthesize(SpeechSynthesisRequest.of("hi"), Duration.ZERO),
                "timeout must be positive");
        assertInvalidDuration(
                () -> image.generate(ImageGenerationRequest.of("p"), Duration.ZERO),
                "timeout must be positive");
        assertInvalidDuration(
                () -> video.generate(VideoGenerationRequest.of("p"), Duration.ofSeconds(-2)),
                "timeout must be positive");
        assertInvalidDuration(
                () -> asyncVideo.wait(JOB, Duration.ZERO), "timeout must be positive");
        assertInvalidDuration(
                () -> asyncVideo.wait(JOB, Duration.ofSeconds(-1)), "timeout must be positive");
        assertInvalidDuration(
                () -> asyncVideo.wait(JOB, Duration.ofSeconds(10), Duration.ZERO),
                "pollInterval must be positive");
        assertInvalidDuration(
                () -> asyncVideo.wait(JOB, Duration.ofSeconds(10), Duration.ofSeconds(-1)),
                "pollInterval must be positive");
        verify(adapter).invoke("python_java_utils.inspect_media_model", pySpeech);
        verify(adapter).invoke("python_java_utils.inspect_media_model", pyTranscription);
        verify(adapter).invoke("python_java_utils.inspect_media_model", pyImage);
        verify(adapter).invoke("python_java_utils.inspect_media_model", pyVideo);
        verifyNoMoreInteractions(adapter);
    }

    @Test
    void alreadyInterruptedThreadFailsFastBeforeCallingPython() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyImage = mock(PyObject.class);
        PyObject pyTranscription = mock(PyObject.class);
        PyObject pySpeech = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyImage))
                .thenReturn(
                        Map.of(
                                "task",
                                "image_generation",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pyTranscription))
                .thenReturn(
                        Map.of(
                                "task",
                                "transcription",
                                "async",
                                true,
                                "connection",
                                "c",
                                "model",
                                "m"));
        when(adapter.invoke("python_java_utils.inspect_media_model", pySpeech))
                .thenReturn(
                        Map.of(
                                "task",
                                "speech_synthesis",
                                "async",
                                false,
                                "connection",
                                "c",
                                "model",
                                "m"));

        BaseImageGenerationModelSetup image =
                (BaseImageGenerationModelSetup) setup(adapter, pyImage);
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> asyncImage = asAsync(image);
        BaseTranscriptionModelSetup transcription =
                (BaseTranscriptionModelSetup) setup(adapter, pyTranscription);
        BaseSpeechSynthesisModelSetup syncSpeech =
                (BaseSpeechSynthesisModelSetup) setup(adapter, pySpeech);

        verify(adapter).invoke("python_java_utils.inspect_media_model", pyImage);
        verify(adapter).invoke("python_java_utils.inspect_media_model", pyTranscription);
        verify(adapter).invoke("python_java_utils.inspect_media_model", pySpeech);

        try {
            Thread.currentThread().interrupt();
            Throwable waitFailure = catchThrowable(() -> asyncImage.wait(JOB));
            assertThat(waitFailure)
                    .isInstanceOf(CancellationException.class)
                    .hasMessage("Media job wait interrupted");
            assertThat(CancellationUtils.isInterruption(waitFailure)).isTrue();
            assertThat(Thread.interrupted()).isTrue();

            for (ThrowingCallable call :
                    List.<ThrowingCallable>of(
                            () -> image.generate("A notebook."),
                            () ->
                                    transcription.transcribe(
                                            AudioBlock.fromUrl(
                                                    "audio/mp3", "https://example.test/in.mp3")),
                            () -> syncSpeech.synthesize("Hello."))) {
                Thread.currentThread().interrupt();
                Throwable failure = catchThrowable(call);
                assertThat(failure)
                        .isInstanceOf(CancellationException.class)
                        .hasMessage("Media model call interrupted");
                assertThat(CancellationUtils.isInterruption(failure)).isTrue();
                assertThat(Thread.interrupted()).isTrue();
            }
            verifyNoMoreInteractions(adapter);
        } finally {
            Thread.interrupted();
        }
    }

    private static BaseMediaModelSetup setup(PythonResourceAdapter adapter, PyObject pyResource) {
        return PythonMediaModelSetup.create(
                adapter, pyResource, new ResourceDescriptor("py.Setup", Map.of()), null);
    }

    private static BaseVideoGenerationModelSetup setupWithOutcome(
            String task, boolean async, Map<String, Object> outcome) {
        PyObject pyResource = mock(PyObject.class);
        PythonResourceAdapter adapter =
                mock(
                        PythonResourceAdapter.class,
                        invocation -> {
                            if ("invoke".equals(invocation.getMethod().getName())
                                    && "python_java_utils.inspect_media_model"
                                            .equals(invocation.getArgument(0))) {
                                return Map.of(
                                        "task",
                                        task,
                                        "async",
                                        async,
                                        "connection",
                                        "c",
                                        "model",
                                        "m");
                            }
                            return outcome;
                        });
        return (BaseVideoGenerationModelSetup) setup(adapter, pyResource);
    }

    private static PyObject mockVideoPyModel(PythonResourceAdapter adapter, boolean async) {
        PyObject pyModel = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.inspect_media_model", pyModel))
                .thenReturn(
                        Map.of(
                                "task",
                                "video_generation",
                                "async",
                                async,
                                "connection",
                                "c",
                                "model",
                                "m"));
        return pyModel;
    }

    private static String stateJson(
            MediaJobStatus status,
            @Nullable String resultJson,
            @Nullable String errorCode,
            @Nullable String errorMessage) {
        return stateJson(JOB, status, resultJson, errorCode, errorMessage);
    }

    private static String stateJson(
            MediaJob job,
            MediaJobStatus status,
            @Nullable String resultJson,
            @Nullable String errorCode,
            @Nullable String errorMessage) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"job\":")
                    .append(MAPPER.writeValueAsString(job))
                    .append(",\"status\":")
                    .append(MAPPER.writeValueAsString(status.getValue()));
            if (resultJson != null) {
                json.append(",\"result\":").append(resultJson);
            }
            if (errorCode != null) {
                json.append(",\"error_code\":").append(MAPPER.writeValueAsString(errorCode));
            }
            if (errorMessage != null) {
                json.append(",\"error_message\":").append(MAPPER.writeValueAsString(errorMessage));
            }
            json.append('}');
            return json.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertNullRejected(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOf(NullPointerException.class).hasMessage(field);
    }

    private static void assertInvalidDuration(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @SuppressWarnings("unchecked")
    private static <Req, T extends org.apache.flink.agents.api.chat.messages.DataContentBlock>
            AsyncJobModel<Req, MediaResult<T>> asAsync(Object model) {
        return (AsyncJobModel<Req, MediaResult<T>>) model;
    }
}
