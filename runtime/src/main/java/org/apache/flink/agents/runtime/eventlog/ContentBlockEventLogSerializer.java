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
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.flink.agents.api.chat.messages.ContentBlock;

import java.io.IOException;

/**
 * Serializes content blocks using their log-safe {@link ContentBlock#sanitize()} projections.
 * Inline media payloads are omitted and media source URLs are sanitized at every log level,
 * including VERBOSE. The level-dependent {@link JsonTruncator} still applies afterwards.
 *
 * <p>Registered only on Event Log mappers, this serializer applies to blocks regardless of their
 * containing message, tool response, or event. Normal serialization for the Java/Python bridge and
 * state recovery preserves the complete payload. Logged blocks must not be deserialized back into
 * content blocks because their media sources no longer contain the original data.
 */
public class ContentBlockEventLogSerializer extends JsonSerializer<ContentBlock> {

    /** The module Event Log mappers register to sanitize all content block subtypes. */
    public static Module module() {
        return new SimpleModule("flink-agents-event-log-content-blocks")
                .addSerializer(ContentBlock.class, new ContentBlockEventLogSerializer());
    }

    @Override
    public void serialize(ContentBlock block, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        serializers.defaultSerializeValue(block.sanitize(), gen);
    }

    @Override
    public void serializeWithType(
            ContentBlock block,
            JsonGenerator gen,
            SerializerProvider serializers,
            TypeSerializer typeSerializer)
            throws IOException {
        // The sanitized map already includes the type discriminator.
        serialize(block, gen, serializers);
    }
}
