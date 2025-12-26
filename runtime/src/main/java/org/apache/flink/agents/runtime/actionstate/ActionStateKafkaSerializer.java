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
package org.apache.flink.agents.runtime.actionstate;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Kafka serializer for {@link ActionState}.
 *
 * <p>This serializer handles the serialization of ActionState instances to byte arrays for storage
 * in Kafka. It uses Jackson ObjectMapper with custom serializers to handle polymorphic Event types
 * and ensures ActionTask is serialized as null.
 */
public class ActionStateKafkaSerializer implements Serializer<ActionState> {

    private static final Logger LOG = LoggerFactory.getLogger(ActionStateKafkaSerializer.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No configuration needed
    }

    @Override
    public byte[] serialize(String topic, ActionState data) {
        if (data == null) {
            return null;
        }

        try {
            return OBJECT_MAPPER.writeValueAsBytes(data);
        } catch (Exception e) {
            LOG.error("Failed to serialize ActionState for topic: {}", topic, e);
            throw new RuntimeException("Failed to serialize ActionState", e);
        }
    }

    @Override
    public void close() {
        // No resources to close
    }

    /** Creates and configures the ObjectMapper for ActionState serialization. */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Add type information for polymorphic Event deserialization
        mapper.addMixIn(Event.class, EventTypeInfoMixin.class);
        mapper.addMixIn(InputEvent.class, EventTypeInfoMixin.class);
        mapper.addMixIn(OutputEvent.class, EventTypeInfoMixin.class);

        // Create a module for custom serializers
        SimpleModule module = new SimpleModule();

        // Custom serializer for ActionTask - always serialize as null
        module.addSerializer(ActionTask.class, new ActionTaskSerializer());

        mapper.registerModule(module);

        return mapper;
    }

    /** Mixin to add type information for Event hierarchy. */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            include = JsonTypeInfo.As.PROPERTY,
            property = "@class")
    public abstract static class EventTypeInfoMixin {}

    /** Custom serializer for ActionTask that always serializes as null. */
    public static class ActionTaskSerializer extends JsonSerializer<ActionTask> {
        @Override
        public void serialize(ActionTask value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeNull();
        }
    }
}
