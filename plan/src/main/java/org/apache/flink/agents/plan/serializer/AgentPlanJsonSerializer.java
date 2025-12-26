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

package org.apache.flink.agents.plan.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.io.IOException;
import java.util.Map;

public class AgentPlanJsonSerializer extends StdSerializer<AgentPlan> {

    public AgentPlanJsonSerializer() {
        super(AgentPlan.class);
    }

    @Override
    public void serialize(
            AgentPlan agentPlan, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
            throws IOException {
        jsonGenerator.writeStartObject();

        // Serialize actions
        jsonGenerator.writeFieldName("actions");
        jsonGenerator.writeStartObject();
        agentPlan
                .getActions()
                .forEach(
                        (name, action) -> {
                            try {
                                jsonGenerator.writeFieldName(name);
                                serializerProvider
                                        .findValueSerializer(Action.class)
                                        .serialize(action, jsonGenerator, serializerProvider);
                            } catch (IOException e) {
                                throw new RuntimeException("Error writing action: " + name, e);
                            }
                        });
        jsonGenerator.writeEndObject();

        // Serialize event trigger actions
        jsonGenerator.writeFieldName("actions_by_event");
        jsonGenerator.writeStartObject();
        agentPlan
                .getActionsByEvent()
                .forEach(
                        (eventClass, actions) -> {
                            try {
                                jsonGenerator.writeFieldName(eventClass);
                                jsonGenerator.writeStartArray();
                                for (Action action : actions) {
                                    jsonGenerator.writeString(action.getName());
                                }
                                jsonGenerator.writeEndArray();
                            } catch (IOException e) {
                                throw new RuntimeException(
                                        "Error writing event trigger actions for: " + eventClass,
                                        e);
                            }
                        });
        jsonGenerator.writeEndObject();

        // Serialize resource providers
        jsonGenerator.writeFieldName("resource_providers");
        jsonGenerator.writeStartObject();
        for (Map.Entry<ResourceType, Map<String, ResourceProvider>> pair :
                agentPlan.getResourceProviders().entrySet()) {
            ResourceType type = pair.getKey();
            Map<String, ResourceProvider> nameToResourceProvider = pair.getValue();
            jsonGenerator.writeFieldName(type.getValue());
            jsonGenerator.writeStartObject();
            nameToResourceProvider.forEach(
                    (name, resourceProvider) -> {
                        try {
                            jsonGenerator.writeFieldName(name);
                            serializerProvider
                                    .findValueSerializer(ResourceProvider.class)
                                    .serialize(resourceProvider, jsonGenerator, serializerProvider);
                        } catch (IOException e) {
                            throw new RuntimeException(
                                    "Error writing resource provider for: " + name, e);
                        }
                    });
            jsonGenerator.writeEndObject();
        }
        jsonGenerator.writeEndObject();

        // Serialize config data
        jsonGenerator.writeFieldName("config");
        jsonGenerator.writeStartObject();
        jsonGenerator.writeFieldName("conf_data");
        jsonGenerator.writeStartObject();
        agentPlan
                .getConfigData()
                .forEach(
                        (key, value) -> {
                            try {
                                jsonGenerator.writeFieldName(key);
                                jsonGenerator.writeObject(value);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
        jsonGenerator.writeEndObject();
        jsonGenerator.writeEndObject();
    }
}
