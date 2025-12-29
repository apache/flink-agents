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

package org.apache.flink.agents.api.event;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;

import javax.annotation.Nullable;

import java.util.List;

/** Event representing a request for chat. */
public class ChatRequestEvent extends Event {
    private final String model;
    private final List<ChatMessage> messages;
    private final @Nullable Object outputSchema;

    public ChatRequestEvent(
            String model, List<ChatMessage> messages, @Nullable Object outputSchema) {
        this.model = model;
        this.messages = messages;
        this.outputSchema = outputSchema;
    }

    public ChatRequestEvent(String model, List<ChatMessage> messages) {
        this(model, messages, null);
    }

    public String getModel() {
        return model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    @Nullable
    public Object getOutputSchema() {
        return outputSchema;
    }
}
