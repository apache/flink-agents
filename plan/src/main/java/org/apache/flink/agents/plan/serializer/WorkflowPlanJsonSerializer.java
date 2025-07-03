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

import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.WorkflowPlan;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonGenerator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class WorkflowPlanJsonSerializer extends StdSerializer<WorkflowPlan> {

    public WorkflowPlanJsonSerializer() {
        super(WorkflowPlan.class);
    }

    @Override
    public void serialize(
            WorkflowPlan workflowPlan,
            JsonGenerator jsonGenerator,
            SerializerProvider serializerProvider)
            throws IOException {
        jsonGenerator.writeStartObject();

        // Serialize actions
        jsonGenerator.writeFieldName("actions");
        jsonGenerator.writeStartObject();
        workflowPlan
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
        jsonGenerator.writeFieldName("event_trigger_actions");
        jsonGenerator.writeStartObject();
        workflowPlan
                .getEventTriggerActions()
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
    }
}
