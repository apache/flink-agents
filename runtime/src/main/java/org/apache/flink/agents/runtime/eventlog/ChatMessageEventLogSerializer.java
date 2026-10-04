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

package org.apache.flink.agents.runtime.eventlog;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;

import java.io.IOException;

/**
 * The Event Log's {@link ChatMessage} serializer: each content block is written through its own
 * {@link ContentBlock#sanitize()} projection, so media payload bytes and unsanitized URLs never
 * reach the log — at any log level, VERBOSE included. Everything else (role, tool calls, extra
 * args) keeps the regular shape, and the level-dependent {@link JsonTruncator} still applies to the
 * result afterwards at STANDARD.
 *
 * <p>This serializer is registered only on the Event Log mappers via {@link #module()}; the global
 * {@link ChatMessage} wire format — the Java/Python bridge, event serialization, state recovery —
 * is untouched and preserves the complete payload. Logged output is not a faithful {@link
 * ChatMessage} and must never be reconstructed into one: an inline-backed media block drops its
 * {@code data} (so reconstruction fails loudly), and a URL-backed one carries only the stripped
 * URL.
 */
public class ChatMessageEventLogSerializer extends JsonSerializer<ChatMessage> {

    /** The module Event Log mappers register to apply the sanitized {@link ChatMessage} shape. */
    public static Module module() {
        return new SimpleModule("flink-agents-event-log-chat-messages")
                .addSerializer(ChatMessage.class, new ChatMessageEventLogSerializer());
    }

    @Override
    public void serialize(ChatMessage message, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("role", message.getRole().getValue());
        gen.writeArrayFieldStart("blocks");
        for (ContentBlock block : message.getBlocks()) {
            gen.writeObject(block.sanitize());
        }
        gen.writeEndArray();
        gen.writeObjectField("tool_calls", message.getToolCalls());
        gen.writeObjectField("extra_args", message.getExtraArgs());
        gen.writeEndObject();
    }
}
