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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event representing a request for chat. */
public class ChatRequestEvent extends Event {

    public static final String EVENT_TYPE = "_chat_request_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ChatRequestEvent(
            String model, List<ChatMessage> messages, @Nullable Object outputSchema) {
        super(EVENT_TYPE);
        setAttr("model", model);
        setAttr("messages", messages);
        if (outputSchema != null) {
            setAttr("output_schema", outputSchema);
        }
    }

    public ChatRequestEvent(String model, List<ChatMessage> messages) {
        this(model, messages, null);
    }

    public ChatRequestEvent(UUID id, Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
    }

    /**
     * Reconstructs a typed ChatRequestEvent from a base Event, deserializing nested types.
     *
     * @param event the base event containing chat request data in attributes
     * @return a typed ChatRequestEvent
     */
    @SuppressWarnings("unchecked")
    public static ChatRequestEvent fromEvent(Event event) {
        Map<String, Object> attrs = new HashMap<>(event.getAttributes());
        List<?> rawMessages = (List<?>) attrs.get("messages");
        if (rawMessages != null) {
            List<ChatMessage> messages = new ArrayList<>();
            for (Object m : rawMessages) {
                if (m instanceof ChatMessage) {
                    messages.add((ChatMessage) m);
                } else if (m instanceof Map) {
                    messages.add(MAPPER.convertValue(m, ChatMessage.class));
                }
            }
            attrs.put("messages", messages);
        }
        ChatRequestEvent result = new ChatRequestEvent(event.getId(), attrs);
        if (event.hasSourceTimestamp()) {
            result.setSourceTimestamp(event.getSourceTimestamp());
        }
        return result;
    }

    @JsonIgnore
    public String getModel() {
        return (String) getAttr("model");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getMessages() {
        return (List<ChatMessage>) getAttr("messages");
    }

    @JsonIgnore
    @Nullable
    public Object getOutputSchema() {
        return getAttr("output_schema");
    }
}
