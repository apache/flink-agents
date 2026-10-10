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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/** Reads TraceRecord JSON, preserving supplied identities and timestamps. */
final class TraceRecordJsonDeserializer extends JsonDeserializer<TraceRecord> {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public TraceRecord deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode root = mapper.readTree(parser);
        if (!root.isObject()) {
            throw JsonMappingException.from(parser, "A TraceRecord must be a JSON object.");
        }
        try {
            TraceContext context =
                    TraceContext.fromExistingIds(
                            string(root, "inputRunId", parser),
                            string(root, "businessKey", parser),
                            string(root, "agentName", parser),
                            string(root, "executionId", parser),
                            string(root, "parentExecutionId", parser),
                            string(root, "entityType", parser),
                            string(root, "entityName", parser),
                            objectMap(root, "entityMetadata", false, mapper, parser));
            return new TraceRecord(
                    context,
                    string(root, "timestamp", parser),
                    string(root, "status", parser),
                    string(root, "problemCategory", parser),
                    objectMap(root, "attributes", true, mapper, parser));
        } catch (IllegalArgumentException error) {
            throw JsonMappingException.from(parser, error.getMessage(), error);
        }
    }

    private static String string(JsonNode root, String field, JsonParser parser)
            throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw JsonMappingException.from(
                    parser, "TraceRecord '" + field + "' must be a string.");
        }
        return value.textValue();
    }

    private static Map<String, Object> objectMap(
            JsonNode root, String field, boolean required, ObjectMapper mapper, JsonParser parser)
            throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw JsonMappingException.from(
                        parser, "TraceRecord '" + field + "' must be an object.");
            }
            return Collections.emptyMap();
        }
        if (!value.isObject()) {
            throw JsonMappingException.from(
                    parser, "TraceRecord '" + field + "' must be an object.");
        }
        return mapper.convertValue(value, MAP_TYPE);
    }
}
