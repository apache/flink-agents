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

package org.apache.flink.agents.api.subagent;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;

/**
 * Renders the input type a sub-agent declares as the JSON Schema a chat model is told about, so
 * that a sub-agent which types its arguments does not also have to spell out their schema.
 *
 * <p>Rendering goes through the same Jackson generator {@code ReActAgent} renders a POJO output
 * schema with, which keeps the two type-to-schema paths in this module on one implementation and
 * adds no dependency.
 */
final class InputSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InputSchemas() {}

    /**
     * The schema of {@code type}, or {@code null} when the type states no shape a model could build
     * a call from. That is {@link Object}, the type a sub-agent declares when it declares none, and
     * any type that does not render as a JSON object, because the parameters of a callable must be
     * one.
     *
     * @throws IllegalArgumentException if rendering the type fails, which is a declaration mistake
     *     worth failing on rather than dropping silently.
     */
    @Nullable
    static String fromType(@Nullable Class<?> type) {
        if (type == null || type == Object.class) {
            return null;
        }
        JsonNode schema = render(type);
        return "object".equals(schema.path("type").asText()) ? schema.toString() : null;
    }

    private static JsonNode render(Class<?> type) {
        try {
            return MAPPER.generateJsonSchema(type).getSchemaNode();
        } catch (JsonMappingException | IllegalArgumentException e) {
            // Both are reachable: a class whose getters disagree on a property name fails the
            // mapping, and one the generator has no JSON-object serializer for is refused with an
            // IllegalArgumentException naming no remedy.
            throw new IllegalArgumentException(
                    String.format(
                            "Sub-agent input type %s cannot be rendered as a JSON Schema, so it"
                                    + " cannot be declared to a chat model. Declare an input schema"
                                    + " explicitly, or use an input type whose fields are all"
                                    + " JSON-Schema-renderable. Rendering it reported: %s",
                            type.getName(), e.getMessage()),
                    e);
        } catch (StackOverflowError e) {
            // The generator carries no cycle guard, so a class that reaches itself through its own
            // members recurses until the stack is gone. A separate clause rather than another type
            // on the union above because the error carries no message to quote, so this case has to
            // name the cause itself.
            throw new IllegalArgumentException(
                    String.format(
                            "Sub-agent input type %s is self-referential, so rendering it as a"
                                    + " JSON Schema does not terminate and it cannot be declared to"
                                    + " a chat model. Declare an input schema explicitly, or use an"
                                    + " input type that does not refer back to itself.",
                            type.getName()),
                    e);
        }
    }
}
