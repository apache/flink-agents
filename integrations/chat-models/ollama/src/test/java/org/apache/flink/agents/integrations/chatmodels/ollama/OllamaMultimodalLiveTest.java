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

package org.apache.flink.agents.integrations.chatmodels.ollama;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sends a real image through the Ollama connection, to check that the server accepts what {@link
 * OllamaMultimodalTest} pins.
 *
 * <p>Skipped unless {@code OLLAMA_VISION_MODEL} names a vision model the server already has (for
 * example {@code qwen2.5vl:3b}); {@code OLLAMA_ENDPOINT} defaults to {@code
 * http://localhost:11434}.
 */
class OllamaMultimodalLiveTest {

    private static final String MODEL = System.getenv("OLLAMA_VISION_MODEL");

    @BeforeAll
    static void requireModel() {
        assumeTrue(MODEL != null && !MODEL.isBlank(), "OLLAMA_VISION_MODEL is not set");
    }

    @Test
    @DisplayName("A base64 image is accepted")
    void testImage() throws Exception {
        String endpoint = System.getenv("OLLAMA_ENDPOINT");
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument(
                                "endpoint",
                                endpoint == null || endpoint.isBlank()
                                        ? "http://localhost:11434"
                                        : endpoint)
                        .build();
        OllamaChatModelConnection connection =
                new OllamaChatModelConnection(
                        descriptor, ResourceContext.fromGetResource((name, type) -> null));
        Map<String, Object> params = new HashMap<>();
        params.put("model", MODEL);

        ChatMessage response =
                connection.chat(
                        List.of(
                                ChatMessage.user(
                                        List.of(
                                                TextBlock.of(
                                                        "What color is this image? Answer in one"
                                                                + " word."),
                                                ImageBlock.fromBase64(
                                                        "image/png", redSquarePng())))),
                        List.of(),
                        params);

        assertThat(response.getText()).isNotBlank();
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
}
