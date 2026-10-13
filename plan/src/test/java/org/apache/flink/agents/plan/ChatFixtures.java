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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.event.ToolRequestEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builders for scripted model replies and tool requests in execution tests. */
public final class ChatFixtures {
    private ChatFixtures() {}

    public static ToolCallBlock call(Map<String, Object> data) {
        Map<String, Object> function = (Map<String, Object>) data.get("function");
        return new ToolCallBlock(
                (String) data.get("id"),
                (String) function.get("name"),
                (Map<String, Object>) function.get("arguments"));
    }

    public static ToolRequestEvent request(String model, List<Map<String, Object>> calls) {
        return new ToolRequestEvent(
                model, calls.stream().map(ChatFixtures::call).collect(Collectors.toList()));
    }

    public static ChatResult response(String text) {
        return new ChatResult(
                org.apache.flink.agents.api.chat.messages.ChatMessage.assistant(
                        text.isEmpty() ? List.of() : List.of(new TextBlock(text))));
    }

    public static ChatResult response(String text, List<Map<String, Object>> calls) {
        return response(text, calls, Map.of());
    }

    public static ChatResult response(String text, Map<String, Object> fields) {
        return response(text, List.of(), fields);
    }

    public static ChatResult response(
            String text, List<Map<String, Object>> calls, Map<String, Object> fields) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) blocks.add(new TextBlock(text));
        calls.forEach(c -> blocks.add(call(c)));
        String reason = (String) fields.get("finish_reason");
        Object input = fields.get("promptTokens");
        Object output = fields.get("completionTokens");
        return new ChatResult(
                org.apache.flink.agents.api.chat.messages.ChatMessage.assistant(blocks),
                (String) fields.get("model_name"),
                null,
                new TokenUsage(
                        input instanceof Number ? ((Number) input).longValue() : null,
                        output instanceof Number ? ((Number) output).longValue() : null),
                reason,
                Map.of());
    }
}
