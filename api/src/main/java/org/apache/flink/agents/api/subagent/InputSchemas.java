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

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;

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
     *
     * <p>Both steps run at every level, since Jackson inlines a nested bean under its property
     * rather than referring out to it: an object reached through a property, or through an array's
     * items, gets its own {@code required} and its own {@code byte[]} rewritten, which is what
     * pydantic does for a nested model. Properties are matched on the name they carry in the
     * schema, not the Java field name, so a {@code boolean isActive} (schema name {@code active}),
     * a getter renamed with {@code @JsonProperty}, and a getter with no field are each judged by
     * the type the schema actually shows rather than missed and wrongly required.
     */
    private static JsonNode alignWithCrossLanguageForm(Class<?> type, JsonNode schema) {
        if (schema instanceof ObjectNode) {
            alignObject(type, (ObjectNode) schema);
        }
        return schema;
    }

    /**
     * Aligns one object node in place: drops the {@code required} Jackson left, recomputes it from
     * the properties whose Java type carries no implicit default, and recurses into nested objects.
     */
    private static void alignObject(Class<?> type, ObjectNode object) {
        JsonNode properties = object.get("properties");
        object.remove("required");
        if (properties == null || !properties.isObject()) {
            // Without properties this is a scalar, array, or null schema, not an object one;
            // fromType declares only object schemas and returns null for the rest, so there is
            // nothing here to align. A nested node reaches here only through an object property.
            return;
        }
        Map<String, JavaType> typeByProperty = propertyTypes(type);
        List<String> required = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JavaType propertyType = typeByProperty.get(name);
            alignProperty(entry.getValue(), propertyType);
            // A property a model must send is one whose Java type carries no implicit default: a
            // primitive always has one (0, false), so it may be omitted, while an object reference
            // defaults to null and must be sent. This is pydantic's rule, where a field with no
            // default is required. A field given an initializer is still judged by its type, since
            // introspection does not see the initializer; an input type is a plain data holder and
            // should not carry one. A property introspection does not report is judged by the safe
            // reading and required, so a model is never told it may drop something the sub-agent
            // reads.
            if (propertyType == null || !propertyType.isPrimitive()) {
                required.add(name);
            }
        }
        if (!required.isEmpty()) {
            ArrayNode requiredNode = object.putArray("required");
            required.forEach(requiredNode::add);
        }
    }

    /**
     * Aligns one property node in place: rewrites it when it is a {@code byte[]}, and otherwise
     * recurses when it is a nested object, or an array of them, so each gets the same step.
     */
    private static void alignProperty(JsonNode property, @Nullable JavaType propertyType) {
        if (!(property instanceof ObjectNode)) {
            return;
        }
        ObjectNode propertyObject = (ObjectNode) property;
        if (rewriteBytes(propertyObject)) {
            return;
        }
        String schemaType = propertyObject.path("type").asText();
        if ("object".equals(schemaType)) {
            // A nested bean, which Jackson inlines here: align it as an object in its own right.
            // Without a reported type there is nothing to judge its properties by, so drop the
            // marker Jackson left rather than guess a required list for it.
            Class<?> nested = propertyType == null ? null : propertyType.getRawClass();
            if (nested != null) {
                alignObject(nested, propertyObject);
            } else {
                propertyObject.remove("required");
            }
        } else if ("array".equals(schemaType)) {
            JsonNode items = propertyObject.get("items");
            if (items instanceof ObjectNode && "object".equals(items.path("type").asText())) {
                // An array of beans shares one item schema: align it through the element type.
                JavaType content = propertyType == null ? null : propertyType.getContentType();
                Class<?> element = content == null ? null : content.getRawClass();
                if (element != null) {
                    alignObject(element, (ObjectNode) items);
                } else {
                    ((ObjectNode) items).remove("required");
                }
            }
        }
    }

    /**
     * Rewrites a {@code byte[]} property, which Jackson renders as an array of {@code byte}, and
     * reports whether it did, so the caller stops there rather than reading it as a nested object.
     */
    private static boolean rewriteBytes(ObjectNode property) {
        JsonNode items = property.get("items");
        if ("array".equals(property.path("type").asText())
                && items != null
                && "byte".equals(items.path("type").asText())) {
            property.remove("items");
            property.put("type", "string");
            property.put("format", "binary");
            return true;
        }
        return false;
    }

    /**
     * The Java type of each property of {@code type}, keyed by the name it carries in the schema.
     * Going through Jackson's own introspection rather than the declared fields is what makes the
     * keys line up with the schema the generator produced from the same config: it applies the same
     * bean naming ({@code isActive} to {@code active}), the same {@code @JsonProperty} renames, and
     * sees a getter that has no field behind it.
     */
    private static Map<String, JavaType> propertyTypes(Class<?> type) {
        Map<String, JavaType> typeByName = new HashMap<>();
        BeanDescription bean =
                MAPPER.getSerializationConfig().introspect(MAPPER.constructType(type));
        for (BeanPropertyDefinition property : bean.findProperties()) {
            AnnotatedMember member = property.getPrimaryMember();
            if (member == null) {
                member = property.getAccessor();
            }
            if (member != null) {
                typeByName.put(property.getName(), member.getType());
            }
        }
        return typeByName;
    }
}
