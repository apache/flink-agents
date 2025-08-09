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

package org.apache.flink.agents.api.chat;

import org.apache.flink.agents.api.chat.messages.AssistantMessage;
import org.apache.flink.agents.api.chat.messages.Message;
import org.apache.flink.agents.api.chat.messages.UserMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModel;
import org.apache.flink.agents.api.chat.model.ChatResponse;
import org.apache.flink.agents.api.chat.model.Generation;
import org.apache.flink.agents.api.prompt.Prompt;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class SimpleBaseChatModel implements BaseChatModel {

    @Override
    public ChatResponse call(Prompt request) {
        List<Message> messages = request.getInstructions();
        if (messages == null || messages.isEmpty()) {
            AssistantMessage response =
                    new AssistantMessage(
                            "No messages provided.", Collections.emptyList(), new HashMap<>());
            Generation generation = new Generation(response);
            return new ChatResponse(Collections.singletonList(generation));
        }

        Message last = messages.get(messages.size() - 1);
        AssistantMessage response;
        if (last instanceof UserMessage) {
            response =
                    new AssistantMessage(
                            "Echo: " + last.getText(), Collections.emptyList(), new HashMap<>());
        } else {
            response =
                    new AssistantMessage(
                            "I can only echo user messages.",
                            Collections.emptyList(),
                            new HashMap<>());
        }
        Generation generation = new Generation(response);
        return new ChatResponse(Collections.singletonList(generation));
    }
}
