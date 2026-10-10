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

package org.apache.flink.agents.api.trace;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/** Writes TraceRecord JSON with flattened context fields. */
final class TraceRecordJsonSerializer extends JsonSerializer<TraceRecord> {

    @Override
    public void serialize(TraceRecord record, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        TraceContext context = record.getContext();
        gen.writeStartObject();
        gen.writeStringField("timestamp", record.getTimestamp());
        writeStringIfPresent(gen, "inputRunId", context.getInputRunId());
        writeStringIfPresent(gen, "businessKey", context.getBusinessKey());
        writeStringIfPresent(gen, "agentName", context.getAgentName());
        gen.writeStringField("entityType", context.getEntityType());
        gen.writeStringField("entityName", context.getEntityName());
        writeStringIfPresent(gen, "executionId", context.getExecutionId());
        writeStringIfPresent(gen, "parentExecutionId", context.getParentExecutionId());
        if (!context.getEntityMetadata().isEmpty()) {
            gen.writeObjectField("entityMetadata", context.getEntityMetadata());
        }
        writeStringIfPresent(gen, "status", record.getStatus());
        writeStringIfPresent(gen, "problemCategory", record.getProblemCategory());
        gen.writeObjectField("attributes", record.getAttributes());
        gen.writeEndObject();
    }

    private static void writeStringIfPresent(JsonGenerator gen, String field, String value)
            throws IOException {
        if (value != null) {
            gen.writeStringField(field, value);
        }
    }
}
