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

package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.apache.flink.agents.integration.test.MultimodalChatAgent.VISION_MODEL;

/** Runs {@link MultimodalChatAgent} on Flink with a real image and an Ollama vision model. */
public class MultimodalChatIntegrationTest extends OllamaPreparationUtils {

    private final boolean ollamaReady;

    public MultimodalChatIntegrationTest() throws IOException {
        ollamaReady = pullModel(VISION_MODEL);
    }

    @Test
    public void testImageReachesTheModelThroughTheAgent() throws Exception {
        Assumptions.assumeTrue(ollamaReady, String.format("%s is not ready", VISION_MODEL));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<String> images = env.fromData(redSquarePng());

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        DataStream<Object> answers =
                agentsEnv
                        .fromDataStream(images, (KeySelector<String, String>) image -> "image")
                        .apply(new MultimodalChatAgent())
                        .toDataStream();
        CloseableIterator<Object> results = answers.collectAsync();
        agentsEnv.execute();

        List<String> responses = new ArrayList<>();
        while (results.hasNext()) {
            responses.add(String.valueOf(results.next()));
        }
        // The model's wording varies; the point is that the image reached it and it answered.
        Assertions.assertEquals(1, responses.size(), "Unexpected responses: " + responses);
        Assertions.assertFalse(responses.get(0).isBlank(), "The model returned an empty answer");
    }

    /** A 64x64 red PNG; Qwen vision processors reject sides under 32 pixels. */
    private static String redSquarePng() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
