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
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests for {@link BaseMediaModelSetup} and the four capability setup classes. */
class MediaModelSetupTest {
    private static final class ConcreteSpeechSetup extends BaseSpeechSynthesisModelSetup {
        ConcreteSpeechSetup(
                ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public MediaResult<AudioBlock> synthesize(
                SpeechSynthesisRequest request, @Nullable Duration timeout) {
            return RecordingConnection.mediaResult(
                    AudioBlock.fromUrl("audio/wav", "https://example.test/a.wav"));
        }
    }

    private static final class ConcreteTranscriptionSetup extends BaseTranscriptionModelSetup {
        ConcreteTranscriptionSetup(
                ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public MediaResult<TextBlock> transcribe(
                TranscriptionRequest request, @Nullable Duration timeout) {
            return RecordingConnection.mediaResult(new TextBlock("ok"));
        }
    }

    private static final class ConcreteImageSetup extends BaseImageGenerationModelSetup {
        ConcreteImageSetup(
                ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public MediaResult<ImageBlock> generate(
                ImageGenerationRequest request, @Nullable Duration timeout) {
            return RecordingConnection.mediaResult(
                    ImageBlock.fromUrl("image/png", "https://example.test/img.png"));
        }
    }

    private static final class ConcreteVideoSetup extends BaseVideoGenerationModelSetup {
        ConcreteVideoSetup(
                ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public MediaResult<VideoBlock> generate(
                VideoGenerationRequest request, @Nullable Duration timeout) {
            return RecordingConnection.mediaResult(
                    VideoBlock.fromUrl("video/mp4", "https://example.test/vid.mp4"));
        }
    }

    private static final class RecordingConnection extends BaseMediaModelConnection {
        boolean closed;

        private RecordingConnection() {
            super(new ResourceDescriptor("fake.Connection", Map.of()), null);
        }

        @Override
        public void close() {
            closed = true;
        }

        private static <T extends DataContentBlock> MediaResult<T> mediaResult(T block) {
            return new MediaResult<>(List.of(block), null, null, null, null);
        }
    }

    @Test
    void validatesDescriptorConfiguration() {
        assertThatThrownBy(
                        () ->
                                new ConcreteImageSetup(
                                        new ResourceDescriptor(
                                                ConcreteImageSetup.class.getName(), Map.of()),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");

        assertThatThrownBy(
                        () ->
                                new ConcreteImageSetup(
                                        new ResourceDescriptor(
                                                ConcreteImageSetup.class.getName(),
                                                Map.of("model", "m")),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");

        assertThatThrownBy(
                        () ->
                                new ConcreteImageSetup(
                                        new ResourceDescriptor(
                                                ConcreteImageSetup.class.getName(),
                                                Map.of("connection", "   ", "model", "m")),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");

        assertThatThrownBy(
                        () ->
                                new ConcreteImageSetup(
                                        new ResourceDescriptor(
                                                ConcreteImageSetup.class.getName(),
                                                Map.of("connection", "media", "model", "   ")),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");

        assertThatThrownBy(
                        () ->
                                new ConcreteSpeechSetup(
                                        new ResourceDescriptor(
                                                ConcreteSpeechSetup.class.getName(),
                                                Map.of(
                                                        "connection",
                                                        "media",
                                                        "model",
                                                        "m",
                                                        "voice",
                                                        "  ")),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("voice");

        ConcreteVideoSetup videoSetup =
                new ConcreteVideoSetup(
                        new ResourceDescriptor(
                                ConcreteVideoSetup.class.getName(),
                                Map.of("connection", "media", "model", "m")),
                        null);
        assertThat(videoSetup.getResourceType()).isEqualTo(ResourceType.MEDIA_MODEL);
        assertThat(videoSetup.getConnectionName()).isEqualTo("media");
        assertThat(videoSetup.getModel()).isEqualTo("m");

        ConcreteSpeechSetup speechSetup =
                new ConcreteSpeechSetup(
                        new ResourceDescriptor(
                                ConcreteSpeechSetup.class.getName(),
                                Map.of("connection", "media", "model", "tts-1", "voice", "Cherry")),
                        null);
        assertThat(speechSetup.getConnectionName()).isEqualTo("media");
        assertThat(speechSetup.getModel()).isEqualTo("tts-1");
        assertThat(speechSetup.getVoice()).isEqualTo("Cherry");

        assertThat(
                        new ConcreteTranscriptionSetup(
                                        new ResourceDescriptor(
                                                ConcreteTranscriptionSetup.class.getName(),
                                                Map.of("connection", "media", "model", "asr")),
                                        null)
                                .getModel())
                .isEqualTo("asr");
    }

    @Test
    void openRejectsMissingContextAndWrongResourceType() throws Exception {
        ConcreteImageSetup noCtx =
                new ConcreteImageSetup(
                        new ResourceDescriptor(
                                ConcreteImageSetup.class.getName(),
                                Map.of("connection", "c", "model", "m")),
                        null);
        assertThatThrownBy(noCtx::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ResourceContext is required");

        ResourceContext wrongCtx = mock(ResourceContext.class);
        Resource wrongResource = mock(Resource.class);
        when(wrongCtx.getResource("c", ResourceType.MEDIA_MODEL_CONNECTION))
                .thenReturn(wrongResource);
        ConcreteImageSetup wrongSetup =
                new ConcreteImageSetup(
                        new ResourceDescriptor(
                                ConcreteImageSetup.class.getName(),
                                Map.of("connection", "c", "model", "m")),
                        wrongCtx);
        assertThatThrownBy(wrongSetup::open)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a BaseMediaModelConnection");
    }

    @Test
    void convenienceOverloadsDelegateWithNullTimeout() {
        ResourceDescriptor speechDesc =
                new ResourceDescriptor(
                        BaseSpeechSynthesisModelSetup.class.getName(),
                        Map.of("connection", "c", "model", "tts", "voice", "Cherry"));
        AtomicReference<Duration> observedTimeout = new AtomicReference<>(Duration.ofSeconds(1));
        BaseSpeechSynthesisModelSetup speech =
                new BaseSpeechSynthesisModelSetup(speechDesc, null) {
                    @Override
                    public MediaResult<AudioBlock> synthesize(
                            SpeechSynthesisRequest request, @Nullable Duration timeout) {
                        observedTimeout.set(timeout);
                        return RecordingConnection.mediaResult(
                                AudioBlock.fromUrl("audio/wav", "https://example.test/a.wav"));
                    }
                };
        speech.synthesize("hello");
        assertThat(observedTimeout.get()).isNull();

        observedTimeout.set(Duration.ofSeconds(1));
        ResourceDescriptor asrDesc =
                new ResourceDescriptor(
                        BaseTranscriptionModelSetup.class.getName(),
                        Map.of("connection", "c", "model", "asr"));
        BaseTranscriptionModelSetup transcription =
                new BaseTranscriptionModelSetup(asrDesc, null) {
                    @Override
                    public MediaResult<TextBlock> transcribe(
                            TranscriptionRequest request, @Nullable Duration timeout) {
                        observedTimeout.set(timeout);
                        return RecordingConnection.mediaResult(new TextBlock("ok"));
                    }
                };
        transcription.transcribe(AudioBlock.fromUrl("audio/mp3", "https://example.test/a.mp3"));
        assertThat(observedTimeout.get()).isNull();

        observedTimeout.set(Duration.ofSeconds(1));
        ResourceDescriptor imgDesc =
                new ResourceDescriptor(
                        BaseImageGenerationModelSetup.class.getName(),
                        Map.of("connection", "c", "model", "img"));
        BaseImageGenerationModelSetup image =
                new BaseImageGenerationModelSetup(imgDesc, null) {
                    @Override
                    public MediaResult<ImageBlock> generate(
                            ImageGenerationRequest request, @Nullable Duration timeout) {
                        observedTimeout.set(timeout);
                        return RecordingConnection.mediaResult(
                                ImageBlock.fromUrl("image/png", "https://example.test/img.png"));
                    }
                };
        image.generate("cat");
        assertThat(observedTimeout.get()).isNull();

        observedTimeout.set(Duration.ofSeconds(1));
        ResourceDescriptor vidDesc =
                new ResourceDescriptor(
                        BaseVideoGenerationModelSetup.class.getName(),
                        Map.of("connection", "c", "model", "vid"));
        BaseVideoGenerationModelSetup video =
                new BaseVideoGenerationModelSetup(vidDesc, null) {
                    @Override
                    public MediaResult<VideoBlock> generate(
                            VideoGenerationRequest request, @Nullable Duration timeout) {
                        observedTimeout.set(timeout);
                        return RecordingConnection.mediaResult(
                                VideoBlock.fromUrl("video/mp4", "https://example.test/vid.mp4"));
                    }
                };
        video.generate("dog");
        assertThat(observedTimeout.get()).isNull();
    }

    @Test
    void baseSetupManagesConnectionLifecycleUniformly() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        ResourceContext okCtx = mock(ResourceContext.class);
        when(okCtx.getResource("media", ResourceType.MEDIA_MODEL_CONNECTION))
                .thenReturn(connection);

        ConcreteImageSetup declarative =
                new ConcreteImageSetup(
                        new ResourceDescriptor(
                                ConcreteImageSetup.class.getName(),
                                Map.of("connection", "media", "model", "img")),
                        okCtx);
        assertThatThrownBy(declarative::getConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Call open() before using it");

        declarative.open();
        declarative.open();
        assertThat(declarative.getConnection()).isSameAs(connection);

        declarative.close();
        assertThat(connection.closed).isFalse();
        assertThatThrownBy(declarative::getConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Call open() before using it");
    }
}
