/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.api.prompt;

import org.apache.flink.agents.api.chat.messages.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Messages template implementation. */
public class MessagesTemplate extends PromptTemplate {
    private final List<ChatMessage> messages;

    public MessagesTemplate(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be empty");
        }
        this.messages = new ArrayList<>(messages);
    }

    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    public <T> T match(Function<String, T> onString, Function<List<ChatMessage>, T> onMessages) {
        return onMessages.apply(new ArrayList<>(messages));
    }

    @Override
    public boolean isStringTemplate() {
        return false;
    }

    @Override
    public boolean isMessageTemplate() {
        return true;
    }

    @Override
    public String toString() {
        return "MessagesTemplate{messages=" + messages.size() + " items}";
    }
}
