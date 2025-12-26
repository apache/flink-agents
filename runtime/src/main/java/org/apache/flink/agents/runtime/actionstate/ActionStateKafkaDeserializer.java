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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Kafka deserializer for {@link ActionState}.
 *
 * <p>This deserializer handles the deserialization of byte arrays from Kafka back to ActionState
 * instances. It uses Jackson ObjectMapper with custom deserializers to handle polymorphic Event
 * types and ensures ActionTask is deserialized as null.
 */
public class ActionStateKafkaDeserializer implements Deserializer<ActionState> {

    private static final Logger LOG = LoggerFactory.getLogger(ActionStateKafkaDeserializer.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No configuration needed
    }

    @Override
    public ActionState deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(data, ActionState.class);
        } catch (Exception e) {
            LOG.error("Failed to deserialize ActionState for topic: {}", topic, e);
            throw new RuntimeException("Failed to deserialize ActionState", e);
        }
    }

    @Override
    public void close() {
        // No resources to close
    }

    /** Creates and configures the ObjectMapper for ActionState deserialization. */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Add type information for polymorphic Event deserialization
        mapper.addMixIn(Event.class, EventTypeInfoMixin.class);
        mapper.addMixIn(InputEvent.class, EventTypeInfoMixin.class);
        mapper.addMixIn(OutputEvent.class, EventTypeInfoMixin.class);

        // Create a module for custom deserializers
        SimpleModule module = new SimpleModule();

        // Custom deserializer for ActionTask - always deserialize as null
        module.addDeserializer(ActionTask.class, new ActionTaskDeserializer());

        mapper.registerModule(module);

        return mapper;
    }

    /** Mixin to add type information for Event hierarchy. */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            include = JsonTypeInfo.As.PROPERTY,
            property = "@class")
    public abstract static class EventTypeInfoMixin {}

    /** Custom deserializer for ActionTask that always deserializes as null. */
    public static class ActionTaskDeserializer extends JsonDeserializer<ActionTask> {
        @Override
        public ActionTask deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            // Skip the value and return null
            p.skipChildren();
            return null;
        }
    }
}
