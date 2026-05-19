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

package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;

import java.util.Collections;

/**
 * Java actions referenced by {@code resources/yaml/yaml_cross_language_agent.yaml}.
 *
 * <p>{@code processInput} keyword-routes the incoming text to either {@code math_chat_model}
 * (Python wrapper + Java {@code calculateBMI} tool) or {@code creative_chat_model} (Java native
 * chat model without tools). {@code processChatResponse} forwards the model's content as a
 * plain-string {@link OutputEvent} so the test sink can match on substrings.
 */
public final class YamlCrossLanguageActions {

    private YamlCrossLanguageActions() {}

    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        String text = (String) InputEvent.fromEvent(event).getInput();
        String lower = text.toLowerCase();
        String model =
                (lower.contains("calculate") || lower.contains("bmi"))
                        ? "math_chat_model"
                        : "creative_chat_model";
        ctx.sendEvent(
                new ChatRequestEvent(
                        model,
                        Collections.singletonList(new ChatMessage(MessageRole.USER, text)),
                        null));
    }

    public static void processChatResponse(Event event, RunnerContext ctx) {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        ChatMessage response = chatResponse.getResponse();
        if (response == null || response.getContent() == null) {
            return;
        }
        ctx.sendEvent(new OutputEvent(response.getContent()));
    }
}
