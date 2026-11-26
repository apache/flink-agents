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
package org.apache.flink.agents.api.embedding.model;

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;

import java.util.ArrayList;
import java.util.List;

public class EmbeddingModelUtils {
    /** Validate the entire batch and extract text before a text-only provider calls its service. */
    public static List<String> requireTextInputs(
            List<? extends DataContentBlock> blocks, String provider) {
        if (blocks == null) {
            throw new IllegalArgumentException("embedding blocks must not be null");
        }
        List<String> texts = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            DataContentBlock block = blocks.get(i);
            if (block == null) {
                throw new IllegalArgumentException(
                        "Embedding block at index " + i + " must not be null.");
            }
            if (!(block instanceof TextBlock)) {
                throw UnsupportedContentBlockException.forBlock(
                        provider, block, "the embedding model accepts only text");
            }
            texts.add(((TextBlock) block).getText());
        }
        return texts;
    }

    /** Unwrap a single-input response without losing its usage metadata. */
    static EmbeddingResult<float[]> singleResult(EmbeddingResult<List<float[]>> result) {
        if (result.getEmbeddings().size() != 1) {
            throw new IllegalStateException("Expected exactly one embedding for a single input.");
        }
        return new EmbeddingResult<>(result.getEmbeddings().get(0), result.getTokenUsage());
    }

    public static float[] toFloatArray(List list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof Number) {
                array[i] = ((Number) element).floatValue();
            } else {
                throw new IllegalArgumentException(
                        "Expected numeric value in embedding result, but got: "
                                + element.getClass().getName());
            }
        }
        return array;
    }
}
