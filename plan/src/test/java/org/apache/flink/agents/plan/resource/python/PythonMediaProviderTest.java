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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Java calls a Python media provider through the tagged JSON bridge. */
class PythonMediaProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaJob JOB =
            new MediaJob("dashscope", "model", "video_generation", "job-1", 1, null);
    private static final String JOB_JSON =
            "{\"provider\":\"dashscope\",\"model\":\"model\",\"task\":\"video_generation\",\"job_id\":\"job-1\",\"submitted_at_ms\":1}";

    @Test
    void bindingExposesAsyncJobModelOnlyWhenPythonReportsAsyncCapability() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        when(adapter.invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq("speech_synthesis"),
                        any(),
                        any()))
                .thenReturn(false);
        when(adapter.invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq("image_generation"),
                        eq("sync-image"),
                        any()))
                .thenReturn(false);
        when(adapter.invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq("image_generation"),
                        eq("async-image"),
                        any()))
                .thenReturn(true);
        when(adapter.invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq("video_generation"),
                        any(),
                        any()))
                .thenReturn(true);

        PythonMediaProvider provider = provider(adapter);

        assertThat(provider.speech("tts")).isNotInstanceOf(AsyncJobModel.class);
        assertThat(provider.image("sync-image")).isNotInstanceOf(AsyncJobModel.class);
        assertThat(provider.image("async-image")).isInstanceOf(AsyncJobModel.class);
        assertThat(provider.video("vid")).isInstanceOf(AsyncJobModel.class);
    }

    @Test
    void bindingRejectsInvalidAsyncValidationResultFromPython() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        when(adapter.invoke(
                        eq("python_java_utils.validate_media_binding"), any(), any(), any(), any()))
                .thenReturn("not-a-bool");

        assertThatThrownBy(() -> provider(adapter).image("img"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid MediaProvider binding validation result from Python");
    }

    @Test
    void speechVoiceBindingAndTimeoutForwardCleanly() throws Exception {
        PythonResourceAdapter adapter = mockAdapter(false);
        stubGenerate(
                adapter,
                "{\"content\":[{\"type\":\"audio\",\"media_type\":\"audio/wav\","
                        + "\"source\":{\"type\":\"url\",\"url\":\"https://example.test/out.wav\"}}]}");
        SpeechSynthesisModel speech =
                provider(adapter).speech("tts-model", "Cherry", Map.of("format", "wav"));
        provider(adapter).speech("tts-model", "Cherry");

        MediaResult<AudioBlock> result =
                speech.synthesize(
                        SpeechSynthesisRequest.builder("Hello.")
                                .modelParameter("speed", 1.1)
                                .build(),
                        Duration.ofMillis(2500));

        assertThat(result.getContent())
                .containsExactly(AudioBlock.fromUrl("audio/wav", "https://example.test/out.wav"));
        ArgumentCaptor<String> binding = ArgumentCaptor.forClass(String.class);
        verify(adapter, times(2))
                .invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq("speech_synthesis"),
                        eq("tts-model"),
                        binding.capture());
        assertThat(MAPPER.readTree(binding.getAllValues().get(0)))
                .isEqualTo(
                        MAPPER.readTree(
                                "{\"voice\":\"Cherry\",\"model_parameters\":{\"format\":\"wav\"}}"));
        assertThat(MAPPER.readTree(binding.getAllValues().get(1)))
                .isEqualTo(MAPPER.readTree("{\"voice\":\"Cherry\"}"));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        eq("speech_synthesis"),
                        eq("tts-model"),
                        eq(binding.getAllValues().get(0)),
                        eq("\"Hello.\""),
                        eq(2.5),
                        eq("{\"speed\":1.1}"));
    }

    @Test
    void transcribeReadsThePythonResult() {
        PythonResourceAdapter adapter = mockAdapter(true);
        stubGenerate(
                adapter,
                "{\"content\":[{\"type\":\"text\",\"text\":\"A notebook.\"}],"
                        + "\"usage\":{\"images\":1},\"metadata\":{\"seed\":null}}");

        MediaResult<TextBlock> result =
                provider(adapter)
                        .transcription("model")
                        .transcribe(
                                TranscriptionRequest.of(
                                        AudioBlock.fromUrl(
                                                "audio/mp3", "https://example.test/input.mp3")),
                                Duration.ofMillis(1500));

        assertThat(result.getContent()).containsExactly(TextBlock.of("A notebook."));
        assertThat(result.getUsage()).containsEntry("images", 1);
        assertThat(result.getMetadata()).containsEntry("seed", null);
    }

    @Test
    void fetchWaitAndCancelPreserveTypedContentAndPollingOptions() {
        PythonResourceAdapter adapter = mockAdapter(true);
        String resultJson =
                "{\"content\":[{\"type\":\"video\",\"media_type\":\"video/mp4\",\"source\":{\"type\":\"url\",\"url\":\"https://example.test/out.mp4\"}}]}";
        when(adapter.invoke(
                        eq("python_java_utils.call_media_fetch"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "SUCCESS",
                                "result",
                                "{\"job\":"
                                        + JOB_JSON
                                        + ",\"status\":\"succeeded\",\"result\":"
                                        + resultJson
                                        + "}"));
        when(adapter.invoke(
                        eq("python_java_utils.call_media_wait"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(Map.of("status", "SUCCESS", "result", resultJson));
        when(adapter.invoke(
                        eq("python_java_utils.call_media_cancel"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(Map.of("status", "SUCCESS"));
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> async =
                asAsync(provider(adapter).video("model"));
        VideoBlock expected = VideoBlock.fromUrl("video/mp4", "https://example.test/out.mp4");

        MediaJobState<MediaResult<VideoBlock>> state = async.fetch(JOB);
        assertThat(state.getStatus()).isEqualTo(MediaJobStatus.SUCCEEDED);
        assertThat(state.getResult().getContent()).containsExactly(expected);
        assertThat(async.wait(JOB).getContent()).containsExactly(expected);
        assertThat(async.wait(JOB, null).getContent()).containsExactly(expected);
        assertThat(async.wait(JOB, Duration.ofMillis(1750), Duration.ofSeconds(2)).getContent())
                .containsExactly(expected);
        async.cancel(JOB);

        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_wait"),
                        any(),
                        eq("video_generation"),
                        eq("model"),
                        eq("{}"),
                        any(),
                        eq(1800.0),
                        eq(5.0));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_wait"),
                        any(),
                        eq("video_generation"),
                        eq("model"),
                        eq("{}"),
                        any(),
                        eq(null),
                        eq(5.0));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_wait"),
                        any(),
                        eq("video_generation"),
                        eq("model"),
                        eq("{}"),
                        any(),
                        eq(1.75),
                        eq(2.0));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_cancel"),
                        any(),
                        eq("video_generation"),
                        eq("model"),
                        eq("{}"),
                        any());
    }

    @Test
    void bindingSnapshotsOptionsAndSharesProviderLifetime() throws Exception {
        PythonResourceAdapter adapter = mockAdapter(true);
        stubGenerate(adapter, "{\"content\":[]}");
        PythonMediaProvider provider = provider(adapter);
        InOrder lifecycle = inOrder(adapter);
        lifecycle.verify(adapter).callMethod(null, "open", Map.of());
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("seed", 1);
        VideoGenerationModel video = provider.video("model", parameters);
        parameters.put("seed", 2);
        video.generate("A notebook.");
        lifecycle
                .verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        eq("video_generation"),
                        eq("model"),
                        eq("{\"model_parameters\":{\"seed\":1}}"),
                        any(),
                        any(),
                        any());

        provider.close();
        provider.close();
        lifecycle.verify(adapter).callMethod(null, "close", Map.of());
        verify(adapter, times(1)).callMethod(null, "close", Map.of());
        assertThatThrownBy(() -> video.generate("A notebook."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Media provider is closed");
    }

    @Test
    void imageCallsForwardRequestParametersAndOptionalTimeout() throws Exception {
        PythonResourceAdapter adapter = mockAdapter(true);
        stubGenerate(adapter, "{\"content\":[]}");
        ImageGenerationModel image = provider(adapter).image("image-model", Map.of("n", 1));
        ImageBlock reference =
                ImageBlock.fromUrl("image/png", "https://example.test/reference.png");

        image.generate(
                ImageGenerationRequest.builder("A notebook.").modelParameter("n", 2).build());
        image.generate(
                ImageGenerationRequest.builder("A notebook.")
                        .images(List.of(reference))
                        .modelParameter("n", 2)
                        .build(),
                Duration.ofMillis(750));

        ArgumentCaptor<String> binding = ArgumentCaptor.forClass(String.class);
        verify(adapter)
                .invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq("image_generation"),
                        eq("image-model"),
                        binding.capture());
        assertThat(MAPPER.readTree(binding.getValue()))
                .isEqualTo(MAPPER.readTree("{\"model_parameters\":{\"n\":1}}"));

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        eq("image_generation"),
                        eq("image-model"),
                        eq(binding.getValue()),
                        input.capture(),
                        eq(null),
                        eq("{\"n\":2}"));
        assertThat(MAPPER.readTree(input.getValue()))
                .isEqualTo(MAPPER.readTree("{\"prompt\":\"A notebook.\",\"images\":[]}"));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        eq("image_generation"),
                        eq("image-model"),
                        eq(binding.getValue()),
                        input.capture(),
                        eq(0.75),
                        eq("{\"n\":2}"));
        assertThat(MAPPER.readTree(input.getValue()).path("images"))
                .isEqualTo(MAPPER.valueToTree(List.of(reference)));
    }

    @ParameterizedTest
    @CsvSource({
        "speech_synthesis,audio",
        "transcription,text",
        "image_generation,image",
        "video_generation,video"
    })
    void capabilityViewsDecodeTypedResults(String task, String blockType) {
        PythonResourceAdapter adapter = mockAdapter(!"speech_synthesis".equals(task));
        String block =
                blockType.equals("text")
                        ? "{\"type\":\"text\",\"text\":\"Hello.\"}"
                        : "{\"type\":\""
                                + blockType
                                + "\",\"media_type\":\""
                                + blockType
                                + "/test\",\"source\":{\"type\":\"url\",\"url\":\"https://example.test/result\"}}";
        stubGenerate(adapter, "{\"content\":[" + block + "]}");
        BaseMediaProvider model = provider(adapter);
        MediaResult<?> result;
        switch (task) {
            case "speech_synthesis":
                result = model.speech("model").synthesize("Hello.");
                break;
            case "transcription":
                result =
                        model.transcription("model")
                                .transcribe(
                                        AudioBlock.fromUrl(
                                                "audio/mp3", "https://example.test/input.mp3"));
                break;
            case "image_generation":
                result = model.image("model").generate("A notebook.");
                break;
            case "video_generation":
                result = model.video("model").generate("A notebook.");
                break;
            default:
                throw new AssertionError(task);
        }
        verify(adapter)
                .invoke(
                        eq("python_java_utils.validate_media_binding"),
                        any(),
                        eq(task),
                        eq("model"),
                        eq("{}"));
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        eq(task),
                        eq("model"),
                        eq("{}"),
                        any(),
                        any(),
                        any());
        assertThat(result.getContent())
                .singleElement()
                .isInstanceOf(
                        Map.of(
                                        "audio", AudioBlock.class,
                                        "text", TextBlock.class,
                                        "image", ImageBlock.class,
                                        "video", VideoBlock.class)
                                .get(blockType));
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
        PythonResourceAdapter adapter =
                mockWithDefaultOutcome(Map.of("status", status, "message", "operation stopped"));
        VideoGenerationModel model = provider(adapter).video("model");
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
    void jobErrorAndTimeoutRemainBusinessFailures() {
        PythonResourceAdapter errorAdapter =
                mockWithDefaultOutcome(
                        Map.of(
                                "status", "JOB_ERROR",
                                "job_status", "failed",
                                "code", "BadRequest",
                                "message", "invalid prompt",
                                "job", JOB_JSON));
        Throwable error = catchThrowable(() -> provider(errorAdapter).video("model").generate("p"));
        assertThat(error).isInstanceOf(MediaJobException.class);
        assertThat(((MediaJobException) error).getStatus()).isEqualTo(MediaJobStatus.FAILED);
        assertThat(((MediaJobException) error).getCode()).isEqualTo("BadRequest");
        assertThat(CancellationUtils.isCancellation(error)).isFalse();

        PythonResourceAdapter timeoutAdapter =
                mockWithDefaultOutcome(
                        Map.of(
                                "status", "TIMEOUT",
                                "job_status", "running",
                                "timeout", 12.5,
                                "message", "timed out waiting for job",
                                "job", JOB_JSON));
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> async =
                asAsync(provider(timeoutAdapter).video("model"));
        Throwable timeout = catchThrowable(() -> async.wait(JOB, Duration.ofMillis(12500)));
        assertThat(timeout).isInstanceOf(MediaJobTimeoutException.class);
        MediaJobTimeoutException timeoutError = (MediaJobTimeoutException) timeout;
        assertThat(timeoutError.getStatus()).isEqualTo(MediaJobStatus.RUNNING);
        assertThat(timeoutError.getTimeout()).isEqualTo(Duration.ofMillis(12500));
        assertThat(timeoutError.getJob().getJobId()).isEqualTo("job-1");
        assertThat(CancellationUtils.isCancellation(timeout)).isFalse();

        PythonResourceAdapter zeroTimeoutAdapter =
                mockWithDefaultOutcome(
                        Map.of(
                                "status", "TIMEOUT",
                                "job_status", "pending",
                                "timeout", 0,
                                "message", "timed out immediately",
                                "job", JOB_JSON));
        Throwable zeroTimeout =
                catchThrowable(
                        () ->
                                asAsync(provider(zeroTimeoutAdapter).video("model"))
                                        .wait(JOB, Duration.ZERO));
        assertThat(zeroTimeout).isInstanceOf(MediaJobTimeoutException.class);
        assertThat(((MediaJobTimeoutException) zeroTimeout).getTimeout()).isEqualTo(Duration.ZERO);

        for (Object invalidTimeout :
                new Object[] {
                    null, "12.5", -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1e30,
                }) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "TIMEOUT");
            payload.put("job_status", "running");
            payload.put("timeout", invalidTimeout);
            payload.put("message", "timed out");
            payload.put("job", JOB_JSON);
            PythonResourceAdapter badAdapter = mockWithDefaultOutcome(payload);
            assertThatThrownBy(
                            () ->
                                    asAsync(provider(badAdapter).video("model"))
                                            .wait(JOB, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid MediaProvider timeout from Python");
        }
    }

    @Test
    void bindingAndRequestsAllowNullOptionalInputs() throws Exception {
        PythonResourceAdapter adapter = mockAdapter(true);
        stubGenerate(adapter, "{\"content\":[]}");
        when(adapter.invoke(
                        eq("python_java_utils.call_media_submit"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(Map.of("status", "SUCCESS", "result", JOB_JSON));
        PythonMediaProvider provider = provider(adapter);

        provider.speech("tts-model", (Map<String, Object>) null);
        ImageGenerationModel image = provider.image("img-model", null);
        VideoGenerationModel video = provider.video("vid-model", null);
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

        ArgumentCaptor<String> generateInputs = ArgumentCaptor.forClass(String.class);
        verify(adapter, times(2))
                .invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        any(),
                        any(),
                        eq("{}"),
                        generateInputs.capture(),
                        eq(null),
                        eq("{}"));
        assertThat(MAPPER.readTree(generateInputs.getAllValues().get(0)))
                .isEqualTo(MAPPER.readTree("{\"prompt\":\"A notebook.\",\"images\":[]}"));
        assertThat(MAPPER.readTree(generateInputs.getAllValues().get(1)).path("prompt").asText())
                .isEmpty();
        assertThat(MAPPER.readTree(generateInputs.getAllValues().get(1)).path("media"))
                .isEqualTo(MAPPER.valueToTree(List.of(frame)));

        ArgumentCaptor<String> submitInput = ArgumentCaptor.forClass(String.class);
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_submit"),
                        any(),
                        eq("video_generation"),
                        eq("vid-model"),
                        eq("{}"),
                        submitInput.capture(),
                        eq("{}"));
        assertThat(MAPPER.readTree(submitInput.getValue()))
                .isEqualTo(MAPPER.readTree("{\"prompt\":\"\",\"media\":[]}"));
    }

    @Test
    void rejectsNullRequiredInputsBeforeCallingPython() {
        PythonResourceAdapter adapter = mockAdapter(true);
        PythonMediaProvider provider = provider(adapter);
        SpeechSynthesisModel speech = provider.speech("tts");
        TranscriptionModel transcription = provider.transcription("asr");
        ImageGenerationModel image = provider.image("img");
        VideoGenerationModel video = provider.video("vid");
        AsyncJobModel<SpeechSynthesisRequest, MediaResult<AudioBlock>> asyncSpeech =
                asAsync(speech);
        AsyncJobModel<TranscriptionRequest, MediaResult<TextBlock>> asyncTranscription =
                asAsync(transcription);
        AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> asyncImage = asAsync(image);
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> asyncVideo = asAsync(video);

        verify(adapter).callMethod(null, "open", Map.of());
        verify(adapter, times(4))
                .invoke(eq("python_java_utils.validate_media_binding"), any(), any(), any(), any());

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
        verifyNoMoreInteractions(adapter);
    }

    @Test
    void rejectsInvalidDurationsAndAllowsZeroWaitBudget() {
        PythonResourceAdapter adapter = mockAdapter(true);
        PythonMediaProvider provider = provider(adapter);
        SpeechSynthesisModel speech = provider.speech("tts");
        ImageGenerationModel image = provider.image("img");
        VideoGenerationModel video = provider.video("vid");
        AsyncJobModel<VideoGenerationRequest, MediaResult<VideoBlock>> asyncVideo = asAsync(video);

        verify(adapter).callMethod(null, "open", Map.of());
        verify(adapter, times(3))
                .invoke(eq("python_java_utils.validate_media_binding"), any(), any(), any(), any());

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
                () -> asyncVideo.wait(JOB, Duration.ofSeconds(-1)), "timeout must be non-negative");
        assertInvalidDuration(
                () -> asyncVideo.wait(JOB, Duration.ofSeconds(10), Duration.ZERO),
                "pollInterval must be positive");
        assertInvalidDuration(
                () -> asyncVideo.wait(JOB, Duration.ofSeconds(10), Duration.ofSeconds(-1)),
                "pollInterval must be positive");
        verifyNoMoreInteractions(adapter);

        when(adapter.invoke(
                        eq("python_java_utils.call_media_wait"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(Map.of("status", "SUCCESS", "result", "{\"content\":[]}"));
        asyncVideo.wait(JOB, Duration.ZERO);
        verify(adapter)
                .invoke(
                        eq("python_java_utils.call_media_wait"),
                        any(),
                        eq("video_generation"),
                        eq("vid"),
                        eq("{}"),
                        any(),
                        eq(0.0),
                        eq(5.0));
    }

    private static void assertNullRejected(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOf(NullPointerException.class).hasMessage(field);
    }

    private static void assertInvalidDuration(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @SuppressWarnings("unchecked")
    private <Req, T extends org.apache.flink.agents.api.chat.messages.DataContentBlock>
            AsyncJobModel<Req, MediaResult<T>> asAsync(Object model) {
        return (AsyncJobModel<Req, MediaResult<T>>) model;
    }

    private static void stubGenerate(PythonResourceAdapter adapter, String resultJson) {
        when(adapter.invoke(
                        eq("python_java_utils.call_media_generate"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(Map.of("status", "SUCCESS", "result", resultJson));
    }

    private static PythonResourceAdapter mockWithDefaultOutcome(Map<String, Object> outcome) {
        return mock(
                PythonResourceAdapter.class,
                invocation -> {
                    if ("invoke".equals(invocation.getMethod().getName())
                            && "python_java_utils.validate_media_binding"
                                    .equals(invocation.getArgument(0))) {
                        return true;
                    }
                    return outcome;
                });
    }

    private static PythonResourceAdapter mockAdapter(boolean async) {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        when(adapter.invoke(
                        eq("python_java_utils.validate_media_binding"), any(), any(), any(), any()))
                .thenReturn(async);
        return adapter;
    }

    private static PythonMediaProvider provider(PythonResourceAdapter adapter) {
        PythonMediaProvider provider =
                new PythonMediaProvider(
                        adapter, null, new ResourceDescriptor("unused", Map.of()), null);
        provider.open();
        return provider;
    }
}
