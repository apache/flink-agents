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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
            JsonNode schema = MAPPER.generateJsonSchema(type).getSchemaNode();
            return alignWithCrossLanguageForm(type, schema);
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

    /**
     * Adjusts Jackson's legacy schema so a model reads the same required properties and the same
     * types whichever language declared the sub-agent:
     *
     * <ul>
     *   <li>an object-level {@code required} lists the properties a model must send, and Jackson's
     *       own per-property {@code required} markers are dropped, because they mean "always
     *       present when serialized", the opposite of the "must be sent" a model reads off {@code
     *       required};
     *   <li>{@code byte[]} becomes {@code {"type":"string","format":"binary"}}, the standard JSON
     *       Schema form for binary data: Jackson renders it as an array of {@code byte}, which is
     *       not a JSON Schema type. pydantic gives a {@code bytes} field the same form.
     * </ul>
     */
    private static JsonNode alignWithCrossLanguageForm(Class<?> type, JsonNode schema) {
        ObjectNode object = (ObjectNode) schema;
        JsonNode properties = object.get("properties");
        object.remove("required");
        if (properties == null || !properties.isObject()) {
            // Without properties this is a scalar, array, or null schema, not an object one;
            // fromType declares only object schemas and returns null for the rest, so there is
            // nothing here to align.
            return object;
        }
        Map<String, Boolean> primitiveByProperty = primitiveFields(type);
        List<String> required = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode property = entry.getValue();
            if (property instanceof ObjectNode) {
                ObjectNode propertyObject = (ObjectNode) property;
                propertyObject.remove("required");
                rewriteBytes(propertyObject);
            }
            // A property a model must send is one whose Java type carries no implicit default: a
            // primitive always has one (0, false), so it may be omitted, while an object reference
            // defaults to null and must be sent. This is pydantic's rule, where a field with no
            // default is required. A field given an initializer is still judged by its type, since
            // reflection does not see the initializer; an input type is a plain data holder and
            // should not carry one.
            if (!Boolean.TRUE.equals(primitiveByProperty.get(entry.getKey()))) {
                required.add(entry.getKey());
            }
        }
        if (!required.isEmpty()) {
            ArrayNode requiredNode = object.putArray("required");
            required.forEach(requiredNode::add);
        }
        return object;
    }

    /** Rewrites a {@code byte[]} property, which Jackson renders as an array of {@code byte}. */
    private static void rewriteBytes(ObjectNode property) {
        JsonNode items = property.get("items");
        if ("array".equals(property.path("type").asText())
                && items != null
                && "byte".equals(items.path("type").asText())) {
            property.remove("items");
            property.put("type", "string");
            property.put("format", "binary");
        }
    }

    /** Whether each declared field of {@code type} is a primitive, keyed by field name. */
    private static Map<String, Boolean> primitiveFields(Class<?> type) {
        Map<String, Boolean> primitiveByName = new HashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    primitiveByName.put(field.getName(), field.getType().isPrimitive());
                }
            }
        }
        return primitiveByName;
    }
}
