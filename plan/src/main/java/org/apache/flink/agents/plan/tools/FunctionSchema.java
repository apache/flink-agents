/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.plan.tools;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiled metadata, validation and native binding for one Java function tool. */
public final class FunctionSchema {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SchemaRegistry VALIDATORS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final List<String> names = new ArrayList<>();
    private final List<JavaType> types = new ArrayList<>();
    private final ObjectNode schema;
    private final Schema validator;
    private final ToolMetadata metadata;

    public FunctionSchema(Method method, Collection<String> injectedNames) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Function tools require static methods");
        }
        Set<String> hiddenNames = new HashSet<>(injectedNames);
        hiddenNames.addAll(FunctionTool.getInjectedArgs(method).keySet());
        schema = objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ArrayNode required = (ArrayNode) schema.get("required");
        for (Parameter parameter : method.getParameters()) {
            ToolParam declaration = parameter.getAnnotation(ToolParam.class);
            String name =
                    declaration != null && !declaration.name().isEmpty()
                            ? declaration.name()
                            : parameter.getName();
            if (names.contains(name)) {
                throw new IllegalArgumentException("Duplicate tool parameter: " + name);
            }
            names.add(name);
            JavaType type = MAPPER.constructType(parameter.getParameterizedType());
            types.add(type);
            ObjectNode field = fieldSchema(type, declaration, new HashSet<>());
            properties.set(name, field);
            if (!field.has("default")) {
                required.add(name);
            }
        }
        if (!names.containsAll(hiddenNames)) {
            throw new IllegalArgumentException("Unknown injected parameters: " + hiddenNames);
        }
        validateDefaults(schema);
        validator = VALIDATORS.getSchema(schema);
        ObjectNode visibleSchema = schema.deepCopy();
        ((ObjectNode) visibleSchema.get("properties")).remove(hiddenNames);
        ArrayNode visibleRequired = visibleSchema.putArray("required");
        required.forEach(
                name -> {
                    if (!hiddenNames.contains(name.asText())) {
                        visibleRequired.add(name);
                    }
                });
        Tool annotation = method.getAnnotation(Tool.class);
        metadata =
                new ToolMetadata(
                        method.getName(),
                        annotation == null ? "" : annotation.description(),
                        visibleSchema.toString());
    }

    public ToolMetadata getMetadata() {
        return metadata;
    }

    /** Validate and bind complete arguments without invoking the method. */
    public Object[] bind(Map<String, Object> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("INVALID_ARGUMENT /: type (expected object)");
        }
        rejectNonJson(arguments);
        JsonNode input = MAPPER.valueToTree(arguments);
        applyDefaults(input, schema);
        List<Error> errors = validator.validate(input);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    errors.stream().map(FunctionSchema::errorMessage).sorted().findFirst().get());
        }
        Object[] result = new Object[names.size()];
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            try {
                result[i] = MAPPER.convertValue(input.get(name), types.get(i));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "BINDING_ERROR /" + name + ": " + error.getMessage(), error);
            }
        }
        return result;
    }

    private static String errorMessage(Error error) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < error.getInstanceLocation().getNameCount(); i++) {
            path.append('/')
                    .append(
                            String.valueOf(error.getInstanceLocation().getElement(i))
                                    .replace("~", "~0")
                                    .replace("/", "~1"));
        }
        return "INVALID_ARGUMENT " + (path.length() == 0 ? "/" : path) + ": " + error.getKeyword();
    }

    private static ObjectNode objectSchema() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "object");
        node.putObject("properties");
        node.putArray("required");
        node.put("additionalProperties", false);
        return node;
    }

    private static ObjectNode fieldSchema(
            JavaType type, ToolParam declaration, Set<JavaType> visiting) {
        ObjectNode node = typeSchema(type, visiting);
        if (declaration == null) {
            return node;
        }
        if (!declaration.description().isEmpty()) {
            node.put("description", declaration.description());
        }
        String jsonType = node.path("type").asText();
        if ((!declaration.minimum().isEmpty() || !declaration.maximum().isEmpty())
                        && !jsonType.equals("number")
                        && !jsonType.equals("integer")
                || (declaration.minLength() >= 0 || declaration.maxLength() >= 0)
                        && !jsonType.equals("string")
                || (declaration.minItems() >= 0 || declaration.maxItems() >= 0)
                        && !jsonType.equals("array")) {
            throw new IllegalArgumentException(
                    "Tool constraint does not match parameter type: " + type);
        }
        if (declaration.nullable()) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("Primitive tool parameters cannot be nullable");
            }
            JsonNode originalType = node.get("type");
            if (originalType != null) {
                node.putArray("type").add(originalType.asText()).add("null");
            }
            if (node.has("enum")) {
                ((ArrayNode) node.get("enum")).addNull();
            }
        }
        if (!declaration.minimum().isEmpty()) {
            BigDecimal minimum = new BigDecimal(declaration.minimum());
            node.put(
                    "minimum",
                    node.has("minimum")
                            ? minimum.max(node.get("minimum").decimalValue())
                            : minimum);
        }
        if (!declaration.maximum().isEmpty()) {
            BigDecimal maximum = new BigDecimal(declaration.maximum());
            node.put(
                    "maximum",
                    node.has("maximum")
                            ? maximum.min(node.get("maximum").decimalValue())
                            : maximum);
        }
        bound(node, "minLength", declaration.minLength());
        bound(node, "maxLength", declaration.maxLength());
        bound(node, "minItems", declaration.minItems());
        bound(node, "maxItems", declaration.maxItems());
        if (!declaration.defaultValue().equals(ToolParam.NO_DEFAULT)) {
            try {
                JsonNode value = MAPPER.readTree(declaration.defaultValue());
                if (value == null) {
                    throw new IllegalArgumentException("Default must contain a JSON value");
                }
                node.set("default", value);
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("Invalid tool default", error);
            }
        } else if (!declaration.required()) {
            if (!declaration.nullable()) {
                throw new IllegalArgumentException(
                        "Optional tool parameters require a default or nullable=true");
            }
            node.putNull("default");
        }
        return node;
    }

    private static void bound(ObjectNode schema, String name, int value) {
        if (value >= 0) {
            schema.put(name, value);
        }
    }

    private static ObjectNode typeSchema(JavaType type, Set<JavaType> visiting) {
        ObjectNode node = MAPPER.createObjectNode();
        Class<?> raw = type.getRawClass();
        if (raw == Object.class) {
            return node;
        } else if (raw == String.class || raw == char.class || raw == Character.class) {
            node.put("type", "string");
            if (raw != String.class) {
                node.put("minLength", 1).put("maxLength", 1);
            }
        } else if (raw == boolean.class || raw == Boolean.class) {
            node.put("type", "boolean");
        } else if (raw == byte.class
                || raw == Byte.class
                || raw == short.class
                || raw == Short.class
                || raw == int.class
                || raw == Integer.class
                || raw == long.class
                || raw == Long.class) {
            node.put("type", "integer");
            long min =
                    raw == byte.class || raw == Byte.class
                            ? Byte.MIN_VALUE
                            : raw == short.class || raw == Short.class
                                    ? Short.MIN_VALUE
                                    : raw == int.class || raw == Integer.class
                                            ? Integer.MIN_VALUE
                                            : Long.MIN_VALUE;
            long max =
                    raw == byte.class || raw == Byte.class
                            ? Byte.MAX_VALUE
                            : raw == short.class || raw == Short.class
                                    ? Short.MAX_VALUE
                                    : raw == int.class || raw == Integer.class
                                            ? Integer.MAX_VALUE
                                            : Long.MAX_VALUE;
            node.put("minimum", min).put("maximum", max);
        } else if (raw == double.class
                || raw == Double.class
                || raw == float.class
                || raw == Float.class
                || raw == BigDecimal.class) {
            node.put("type", "number");
            if (raw != BigDecimal.class) {
                double max =
                        raw == float.class || raw == Float.class
                                ? Float.MAX_VALUE
                                : Double.MAX_VALUE;
                node.put("minimum", -max).put("maximum", max);
            }
        } else if (raw.isEnum()) {
            node.put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object value : raw.getEnumConstants()) {
                values.add(((Enum<?>) value).name());
            }
        } else if (type.isArrayType() || type.isCollectionLikeType()) {
            node.put("type", "array");
            node.set("items", typeSchema(type.getContentType(), visiting));
        } else if (type.isMapLikeType()) {
            if (!type.getKeyType().hasRawClass(String.class)) {
                throw new IllegalArgumentException("Tool maps require String keys");
            }
            node.put("type", "object");
            node.set("additionalProperties", typeSchema(type.getContentType(), visiting));
        } else {
            if (raw.getName().startsWith("java.") || !visiting.add(type)) {
                throw new IllegalArgumentException("Unsupported tool parameter type: " + type);
            }
            node = objectSchema();
            for (BeanPropertyDefinition property :
                    MAPPER.getDeserializationConfig().introspect(type).findProperties()) {
                if (!property.couldDeserialize() || property.getPrimaryMember() == null) {
                    continue;
                }
                ToolParam declaration = property.getPrimaryMember().getAnnotation(ToolParam.class);
                if (declaration != null
                        && (!declaration.name().isEmpty() || declaration.injected())) {
                    throw new IllegalArgumentException(
                            "Nested fields cannot rename or inject tool parameters");
                }
                ObjectNode field = fieldSchema(property.getPrimaryType(), declaration, visiting);
                ((ObjectNode) node.get("properties")).set(property.getName(), field);
                if (!field.has("default")) {
                    ((ArrayNode) node.get("required")).add(property.getName());
                }
            }
            visiting.remove(type);
        }
        return node;
    }

    private static void validateDefaults(JsonNode node) {
        if (node.isObject()) {
            if (node.has("default")
                    && !VALIDATORS.getSchema(node).validate(node.get("default")).isEmpty()) {
                throw new IllegalArgumentException("Invalid tool default: " + node.get("default"));
            }
            node.fields()
                    .forEachRemaining(
                            entry -> {
                                if (!Set.of("default", "enum", "examples", "const")
                                        .contains(entry.getKey())) {
                                    validateDefaults(entry.getValue());
                                }
                            });
        } else if (node.isArray()) {
            node.elements().forEachRemaining(FunctionSchema::validateDefaults);
        }
    }

    private static void applyDefaults(JsonNode input, JsonNode schema) {
        if (input.isObject()) {
            JsonNode properties = schema.path("properties");
            properties
                    .fields()
                    .forEachRemaining(
                            entry -> {
                                if (!input.has(entry.getKey()) && entry.getValue().has("default")) {
                                    ((ObjectNode) input)
                                            .set(
                                                    entry.getKey(),
                                                    entry.getValue().get("default").deepCopy());
                                }
                                if (input.has(entry.getKey())) {
                                    applyDefaults(input.get(entry.getKey()), entry.getValue());
                                }
                            });
            if (schema.path("additionalProperties").isObject()) {
                input.fields()
                        .forEachRemaining(
                                entry -> {
                                    if (!properties.has(entry.getKey())) {
                                        applyDefaults(
                                                entry.getValue(),
                                                schema.get("additionalProperties"));
                                    }
                                });
            }
        } else if (input.isArray()) {
            input.elements().forEachRemaining(value -> applyDefaults(value, schema.path("items")));
        }
    }

    private static void rejectNonJson(Object value) {
        if (value instanceof Map) {
            ((Map<?, ?>) value)
                    .forEach(
                            (key, item) -> {
                                if (!(key instanceof String)) {
                                    throw new IllegalArgumentException(
                                            "INVALID_ARGUMENT /: non-JSON key");
                                }
                                rejectNonJson(item);
                            });
        } else if (value instanceof Collection) {
            ((Collection<?>) value).forEach(FunctionSchema::rejectNonJson);
        } else if (value instanceof Double && !Double.isFinite((Double) value)
                || value instanceof Float && !Float.isFinite((Float) value)) {
            throw new IllegalArgumentException("INVALID_ARGUMENT /: non-JSON value");
        } else if (value != null
                && !(value instanceof String)
                && !(value instanceof Number)
                && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("INVALID_ARGUMENT /: non-JSON value");
        }
    }
}
