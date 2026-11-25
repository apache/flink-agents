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
package org.apache.flink.agents.api.chat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.tools.Tool;

import javax.annotation.Nullable;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Approximates the input tokens of a chat request from its size, for connections whose provider
 * offers no way to count them.
 *
 * <p>The approximation is the same in Java and Python. Text costs one token per four ASCII
 * characters and one token per other character. Every message, tool definition and media block adds
 * a fixed cost. Tool call arguments are measured by their structure, so the result does not depend
 * on how a map is rendered as text; the input schema of a tool is measured as compact JSON, however
 * its text is laid out. Real token counts differ by model; the estimate is reported as {@linkplain
 * TokenEstimate#isApproximate() approximate}.
 */
public final class ApproximateTokenEstimator {

    /** Fixed cost of one message: its role and the framing around its content. */
    static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /** Fixed cost of one tool definition, beside its name, description and input schema. */
    static final int TOOL_OVERHEAD_TOKENS = 8;

    /** Flat cost of one image, audio, video or document block, whatever its size. */
    static final int MEDIA_BLOCK_TOKENS = 1024;

    private static final int UNITS_PER_TOKEN = 4;
    private static final int NON_ASCII_UNITS = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApproximateTokenEstimator() {}

    /**
     * Approximates the input tokens of a request with these messages and tools.
     *
     * @param messages the messages as they would be sent
     * @param tools the tools as they would be bound to the request; null is a request without tools
     */
    public static TokenEstimate estimate(List<ChatMessage> messages, @Nullable List<Tool> tools) {
        long tokens = 0;
        long units = 0;
        for (ChatMessage message : messages) {
            tokens += MESSAGE_OVERHEAD_TOKENS;
            for (ContentBlock block : message.getBlocks()) {
                if (block instanceof TextBlock) {
                    units += textUnits(((TextBlock) block).getText());
                } else if (block instanceof ReasoningBlock) {
                    units += textUnits(((ReasoningBlock) block).getText());
                } else if (block instanceof ToolCallBlock) {
                    ToolCallBlock call = (ToolCallBlock) block;
                    units += textUnits(call.getName()) + valueUnits(call.getInput());
                } else if (block instanceof ToolResultBlock) {
                    for (DataContentBlock nested : ((ToolResultBlock) block).getBlocks()) {
                        if (nested instanceof TextBlock) {
                            units += textUnits(((TextBlock) nested).getText());
                        } else {
                            tokens += MEDIA_BLOCK_TOKENS;
                        }
                    }
                } else {
                    tokens += MEDIA_BLOCK_TOKENS;
                }
            }
        }
        for (Tool tool : tools == null ? List.<Tool>of() : tools) {
            tokens += TOOL_OVERHEAD_TOKENS;
            units +=
                    textUnits(tool.getName())
                            + textUnits(tool.getDescription())
                            + schemaUnits(tool.getMetadata().getInputSchema());
        }
        return TokenEstimate.approximate(tokens + (units + UNITS_PER_TOKEN - 1) / UNITS_PER_TOKEN);
    }

    /**
     * The units of a tool's input schema as compact JSON. The schema is a JSON text, and its layout
     * is not part of what a provider is sent: a tool declared in Python reaches Java with a space
     * after every separator, for example. Measured as compact JSON, a tool costs the same on both
     * sides. A number that the two sides write differently, one with an exponent, can still differ
     * by its digits. A schema that is not JSON is measured as it is written.
     */
    private static long schemaUnits(String schema) {
        if (schema == null) {
            return 0;
        }
        try {
            return textUnits(MAPPER.readTree(schema).toString());
        } catch (Exception e) {
            return textUnits(schema);
        }
    }

    /** One unit per ASCII character and four per other character, counted in code points. */
    private static long textUnits(String text) {
        if (text == null) {
            return 0;
        }
        long units = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            units += codePoint < 128 ? 1 : NON_ASCII_UNITS;
            i += Character.charCount(codePoint);
        }
        return units;
    }

    /** The size of a tool call argument, by its structure rather than by a rendering as text. */
    private static long valueUnits(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Boolean) {
            return 5;
        }
        if (value instanceof Number) {
            return 8;
        }
        if (value instanceof Map) {
            long units = 2;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                units +=
                        textUnits(String.valueOf(entry.getKey()))
                                + 4
                                + valueUnits(entry.getValue())
                                + 1;
            }
            return units;
        }
        if (value instanceof Collection) {
            long units = 2;
            for (Object element : (Collection<?>) value) {
                units += valueUnits(element) + 1;
            }
            return units;
        }
        if (value.getClass().isArray()) {
            // An array is sent as a JSON array, like a list; its text form is only its identity.
            long units = 2;
            for (int i = 0; i < Array.getLength(value); i++) {
                units += valueUnits(Array.get(value, i)) + 1;
            }
            return units;
        }
        return 2 + textUnits(String.valueOf(value));
    }
}
