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

package org.apache.flink.agents.integrations.chatmodels.gemini;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a real tool loop through the Gemini connection, to check that the service accepts the
 * function response parts {@link GeminiMultimodalTest} pins and that the model reads the image.
 *
 * <p>Skipped unless {@code GEMINI_API_KEY} is set. {@code GEMINI_BASE_URL} and {@code
 * GEMINI_MULTIMODAL_MODEL} (default {@code gemini-3.6-flash}) override the endpoint and model.
 * Google documents media in function responses for Gemini 3 models.
 */
class GeminiToolMediaLiveTest {

    private static final String API_KEY = System.getenv("GEMINI_API_KEY");

    @BeforeAll
    static void requireCredentials() {
        assumeTrue(API_KEY != null && !API_KEY.isBlank(), "GEMINI_API_KEY is not set");
    }

    @Test
    @DisplayName("The model calls a tool, gets an image in the tool result and reads it")
    void testModelReadsImageReturnedByTool() throws Exception {
        ResourceDescriptor.Builder descriptor =
                ResourceDescriptor.Builder.newBuilder(GeminiChatModelConnection.class.getName())
                        .addInitialArgument("api_key", API_KEY);
        String baseUrl = System.getenv("GEMINI_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            descriptor.addInitialArgument("base_url", baseUrl);
        }
        GeminiChatModelConnection connection =
                new GeminiChatModelConnection(
                        descriptor.build(),
                        TestResourceContexts.fromGetResource((name, type) -> null));
        List<Tool> tools = List.of(new SwatchTool());
        Map<String, Object> params = Map.of("model", model());
        ChatMessage question =
                ChatMessage.user(
                        "Call get_swatch, then answer with the color of the swatch in one word.");

        ChatResult first = connection.chat(List.of(question), tools, params);
        assertThat(first.getToolCalls()).hasSize(1);

        ToolResultBlock swatch =
                new ToolResultBlock(
                        first.getToolCalls().get(0).getCallId(),
                        List.of(
                                TextBlock.of("The swatch:"),
                                ImageBlock.fromBase64("image/png", redSquarePng())),
                        false);
        // The first answer goes back unchanged: its tool call carries the thought signature that
        // Gemini 3 requires in the next request.
        ChatResult second =
                connection.chat(
                        List.of(question, first.getMessage(), ChatMessage.tool(swatch)),
                        tools,
                        params);

        assertThat(second.getText()).containsPattern("(?i)\\bred\\b");
    }

    private static String model() {
        String value = System.getenv("GEMINI_MULTIMODAL_MODEL");
        return value == null || value.isBlank() ? "gemini-3.6-flash" : value;
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

    /** Offered to the model; the test itself supplies the result, so it is never called. */
    private static class SwatchTool extends Tool {
        SwatchTool() {
            super(
                    new ToolMetadata(
                            "get_swatch",
                            "Returns the color swatch to identify, as an image.",
                            "{\"type\":\"object\",\"properties\":{}}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(List.of());
        }
    }
}
