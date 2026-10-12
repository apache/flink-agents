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

package org.apache.flink.agents.integrations.chatmodels.openai;

import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sends real multimodal requests through the OpenAI Chat Completions connection, to check that the
 * provider accepts the content parts {@link OpenAIChatCompletionsMultimodalTest} pins.
 *
 * <p>Skipped unless {@code OPENAI_API_KEY} is set. {@code OPENAI_API_BASE_URL}, {@code
 * OPENAI_MULTIMODAL_MODEL} (default {@code gpt-4o-mini}) and {@code OPENAI_AUDIO_MODEL} (default
 * {@code gpt-4o-audio-preview}) override the endpoint and models.
 */
class OpenAIChatCompletionsMultimodalLiveTest {

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    @BeforeAll
    static void requireCredentials() {
        assumeTrue(API_KEY != null && !API_KEY.isBlank(), "OPENAI_API_KEY is not set");
    }

    @Test
    @DisplayName("A base64 image is accepted")
    void testImage() throws Exception {
        assertThat(
                        chat(
                                multimodalModel(),
                                TextBlock.of("What color is this image? Answer in one word."),
                                ImageBlock.fromBase64("image/png", redSquarePng())))
                .isNotBlank();
    }

    @Test
    @DisplayName("WAV audio is accepted")
    void testAudio() {
        assertThat(
                        chat(
                                env("OPENAI_AUDIO_MODEL", "gpt-4o-audio-preview"),
                                TextBlock.of("Describe this audio clip in one sentence."),
                                AudioBlock.fromBase64("audio/wav", silentWav())))
                .isNotBlank();
    }

    @Test
    @DisplayName("A named PDF is accepted")
    void testNamedPdf() {
        assertThat(
                        chat(
                                multimodalModel(),
                                TextBlock.of("What text does this PDF contain?"),
                                new DocumentBlock(
                                        "application/pdf",
                                        new Base64Source(helloPdf()),
                                        "hello.pdf",
                                        null,
                                        null)))
                .isNotBlank();
    }

    @Test
    @DisplayName("A PDF without a name is accepted under the default file name")
    void testUnnamedPdf() {
        assertThat(
                        chat(
                                multimodalModel(),
                                TextBlock.of("What text does this PDF contain?"),
                                DocumentBlock.fromBase64("application/pdf", helloPdf())))
                .isNotBlank();
    }

    private static String chat(String model, ContentBlock... blocks) {
        ResourceDescriptor.Builder descriptor =
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                        .addInitialArgument("api_key", API_KEY);
        String apiBaseUrl = System.getenv("OPENAI_API_BASE_URL");
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            descriptor.addInitialArgument("api_base_url", apiBaseUrl);
        }
        OpenAICompletionsConnection connection =
                new OpenAICompletionsConnection(
                        descriptor.build(), ResourceContext.fromGetResource((name, type) -> null));
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        return connection
                .chat(List.of(ChatMessage.user(List.of(blocks))), List.of(), params)
                .getText();
    }

    private static String multimodalModel() {
        return env("OPENAI_MULTIMODAL_MODEL", "gpt-4o-mini");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String redSquarePng() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }

    /** Half a second of 16 kHz mono 16-bit PCM silence. */
    static String silentWav() {
        int sampleRate = 16_000;
        int dataSize = sampleRate / 2 * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(US_ASCII)).putInt(36 + dataSize).put("WAVE".getBytes(US_ASCII));
        wav.put("fmt ".getBytes(US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(US_ASCII)).putInt(dataSize);
        return Base64.getEncoder().encodeToString(wav.array());
    }

    /** A one-page PDF reading "Hello PDF", with a valid cross-reference table. */
    static String helloPdf() {
        String stream = "BT /F1 24 Tf 20 40 Td (Hello PDF) Tj ET";
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Contents 4 0 R"
                    + " /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        };
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ")
                .append(objects.length + 1)
                .append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref)
                .append("\n%%EOF\n");
        return Base64.getEncoder().encodeToString(pdf.toString().getBytes(US_ASCII));
    }
}
