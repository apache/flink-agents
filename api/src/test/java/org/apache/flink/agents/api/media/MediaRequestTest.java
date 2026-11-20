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

import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for typed media request objects and {@link BaseMediaProvider} defaults. */
class MediaRequestTest {

    @Test
    void speechSynthesisRequestIsImmutableAndValidatesInputs() {
        Map<String, Object> params = new HashMap<>();
        params.put("format", "mp3");
        params.put("nullable", null);
        SpeechSynthesisRequest request =
                SpeechSynthesisRequest.builder("Hello.")
                        .voice("Cherry")
                        .modelParameters(params)
                        .modelParameter("speed", 1.2)
                        .build();
        params.put("format", "wav");

        assertThat(request.getText()).isEqualTo("Hello.");
        assertThat(request.getModelParameters())
                .containsEntry("voice", "Cherry")
                .containsEntry("format", "mp3")
                .containsEntry("nullable", null)
                .containsEntry("speed", 1.2);
        assertThatThrownBy(() -> request.getModelParameters().put("extra", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(SpeechSynthesisRequest.of("Hello."))
                .isEqualTo(SpeechSynthesisRequest.builder("Hello.").build());
        assertThatThrownBy(() -> SpeechSynthesisRequest.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text");
        assertThatThrownBy(() -> SpeechSynthesisRequest.builder("Hi").voice(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("voice");
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, "bad");
        assertThatThrownBy(() -> SpeechSynthesisRequest.builder("Hi").modelParameters(nullKey))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key");

        // Explicit voice always wins over modelParameters["voice"] regardless of call order.
        SpeechSynthesisRequest voiceFirst =
                SpeechSynthesisRequest.builder("Hello.")
                        .voice("Explicit")
                        .modelParameters(Map.of("voice", "MapVoice", "format", "mp3"))
                        .build();
        SpeechSynthesisRequest mapFirst =
                SpeechSynthesisRequest.builder("Hello.")
                        .modelParameters(Map.of("voice", "MapVoice", "format", "mp3"))
                        .voice("Explicit")
                        .build();
        assertThat(voiceFirst.getModelParameters())
                .containsEntry("voice", "Explicit")
                .containsEntry("format", "mp3");
        assertThat(mapFirst).isEqualTo(voiceFirst);
    }

    @Test
    void transcriptionRequestIsImmutableAndValidatesInputs() {
        AudioBlock audio = AudioBlock.fromUrl("audio/mp3", "https://example.test/in.mp3");
        TranscriptionRequest request =
                TranscriptionRequest.builder(audio).modelParameter("language", "en").build();

        assertThat(request.getAudio()).isEqualTo(audio);
        assertThat(request.getModelParameters()).containsEntry("language", "en");
        assertThat(TranscriptionRequest.of(audio))
                .isEqualTo(TranscriptionRequest.builder(audio).build());
        assertThatThrownBy(() -> TranscriptionRequest.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("audio");
    }

    @Test
    void imageGenerationRequestIsImmutableAndHandlesOptionalImages() {
        ImageBlock ref = ImageBlock.fromUrl("image/png", "https://example.test/ref.png");
        List<ImageBlock> mutable = new ArrayList<>();
        mutable.add(ref);
        ImageGenerationRequest request =
                ImageGenerationRequest.builder("A notebook.")
                        .images(mutable)
                        .modelParameter("size", "512*512")
                        .build();
        mutable.clear();

        assertThat(request.getPrompt()).isEqualTo("A notebook.");
        assertThat(request.getImages()).containsExactly(ref);
        assertThat(request.getModelParameters()).containsEntry("size", "512*512");
        assertThat(ImageGenerationRequest.builder("A notebook.").images(null).build().getImages())
                .isEmpty();
        assertThat(ImageGenerationRequest.of("A notebook."))
                .isEqualTo(ImageGenerationRequest.builder("A notebook.").build());
        assertThatThrownBy(() -> ImageGenerationRequest.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("prompt");
    }

    @Test
    void videoGenerationRequestNormalizesNullPromptAndMedia() {
        ImageBlock frame = ImageBlock.fromUrl("image/png", "https://example.test/frame.png");
        AudioBlock track = AudioBlock.fromUrl("audio/mp3", "https://example.test/track.mp3");
        VideoGenerationRequest request =
                VideoGenerationRequest.builder(null)
                        .media(List.of(frame, track))
                        .modelParameter("duration", 5)
                        .build();

        assertThat(request.getPrompt()).isEmpty();
        assertThat(request.getMedia()).containsExactly(frame, track);
        assertThat(request.getModelParameters()).containsEntry("duration", 5);
        assertThat(VideoGenerationRequest.builder().media(null).build())
                .isEqualTo(VideoGenerationRequest.of(""));
    }

    @Test
    void baseMediaProviderVoiceOverloadMergesVoiceAndOverridesParameterVoice() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        BaseMediaProvider provider =
                new BaseMediaProvider(new ResourceDescriptor("unused", Map.of()), null) {
                    @Override
                    public SpeechSynthesisModel speech(
                            String model, Map<String, Object> parameters) {
                        captured.set(parameters);
                        return (req, timeout) ->
                                new MediaResult<>(List.of(), null, Map.of(), null, Map.of());
                    }
                };

        provider.speech("tts-model", "Cherry", Map.of("voice", "Old", "format", "mp3"));

        assertThat(captured.get()).containsEntry("voice", "Cherry").containsEntry("format", "mp3");
        assertThatThrownBy(() -> provider.speech("tts-model", (String) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("voice");
        assertThatThrownBy(() -> provider.transcription("asr"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.image("img"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.video("vid"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void capabilityInterfacesAreOrthogonalToAsyncJobModel() {
        assertThat(AsyncJobModel.class.isAssignableFrom(SpeechSynthesisModel.class)).isFalse();
        assertThat(AsyncJobModel.class.isAssignableFrom(TranscriptionModel.class)).isFalse();
        assertThat(AsyncJobModel.class.isAssignableFrom(ImageGenerationModel.class)).isFalse();
        assertThat(AsyncJobModel.class.isAssignableFrom(VideoGenerationModel.class)).isFalse();
    }
}
