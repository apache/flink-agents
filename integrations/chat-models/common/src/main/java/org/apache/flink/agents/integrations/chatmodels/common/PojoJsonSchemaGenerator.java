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
package org.apache.flink.agents.integrations.chatmodels.common;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.generator.impl.PropertySortUtils;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Derives the JSON Schema that chat model connections send as a native output schema. */
public final class PojoJsonSchemaGenerator {

    private static final ObjectMapper ENUM_VALUE_MAPPER = new ObjectMapper();

    private PojoJsonSchemaGenerator() {}

    // Every fixed setting below addresses a concrete way the generated schema otherwise misstates
    // the contract of the class a caller deserializes the response into:
    //
    //   - DRAFT_2020_12 is the dialect pydantic generates on the Python side, so a schema derived
    //     from a Java class states the same contract in the same dialect.
    //   - The PLAIN_JSON preset keeps generation to fields. Under the default FULL_DOCUMENTATION
    //     preset, getters also surface as properties of their own, named after the accessor call,
    //     e.g. "getDerived()".
    //   - Sorting fields before methods and applying no further comparison leaves properties in
    //     declaration order, which is the order pydantic emits.
    //   - The required check of generate marks every field required except an Optional one. The
    //     default marks nothing required, which lets a model omit fields at will, while marking
    //     everything required would force the fields a caller declared omissible.
    //     generateAllRequired marks every field required for callers whose provider expects every
    //     property in the required set, as OpenAI's strict mode does. An Optional field stays
    //     nullable there, so the schema still admits null for it.
    //   - The Jackson module names properties the way an ObjectMapper reads them, honoring
    //     @JsonProperty renames and dropping @JsonIgnore fields, which the required check would
    //     otherwise mark required. A property stated under any other name makes a response that
    //     satisfies the schema fail to deserialize. RESPECT_JSONPROPERTY_REQUIRED stays off, so
    //     @JsonProperty(required = true) does not widen the required set configured here.
    //   - Enums get their own definition, registered before the Jackson module so it takes
    //     precedence over the module's. It lists each constant under the value an ObjectMapper
    //     writes for it, typed as that value. The module's @JsonProperty flattening applies only
    //     when every constant carries @JsonProperty, so in a partly annotated enum it lists the
    //     renamed constants by Java names that fail to deserialize. Its @JsonValue flattening
    //     types numeric values as strings, a schema no value satisfies. An enum without
    //     constants, one whose values cannot be written, or one written as objects or arrays
    //     (such as @JsonFormat(shape = OBJECT)) falls back to the existing enum handling.

    /**
     * Generates the JSON Schema for {@code type}.
     *
     * @param type the class a response is deserialized into
     * @param options additional generator options enabled on top of the fixed settings
     * @return the schema document
     */
    public static ObjectNode generate(Class<?> type, Option... options) {
        return generate(
                type, field -> !Optional.class.equals(field.getRawMember().getType()), options);
    }

    /**
     * Generates the JSON Schema for {@code type} with every field required.
     *
     * <p>The schema differs from the one {@link #generate} derives only in its required set, which
     * also lists an {@code Optional} field. That field's schema admits null, so a response can
     * still send null for it.
     *
     * @param type the class a response is deserialized into
     * @param options additional generator options enabled on top of the fixed settings
     * @return the schema document
     */
    public static ObjectNode generateAllRequired(Class<?> type, Option... options) {
        return generate(type, field -> true, options);
    }

    private static ObjectNode generate(
            Class<?> type, Predicate<FieldScope> requiredCheck, Option... options) {
        SchemaGeneratorConfigBuilder builder =
                new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        for (Option option : options) {
            builder.with(option);
        }
        builder.forTypesInGeneral()
                .withCustomDefinitionProvider(PojoJsonSchemaGenerator::enumDefinition);
        builder.with(
                new JacksonModule(
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE));
        builder.forTypesInGeneral()
                .withPropertySorter(PropertySortUtils.SORT_PROPERTIES_FIELDS_BEFORE_METHODS);
        builder.forFields().withRequiredCheck(requiredCheck);
        return new SchemaGenerator(builder.build()).generateSchema(type);
    }

    private static CustomDefinition enumDefinition(
            ResolvedType javaType, SchemaGenerationContext context) {
        Class<?> enumType = javaType.getErasedType();
        if (!enumType.isEnum()) {
            return null;
        }
        Object[] constants = enumType.getEnumConstants();
        if (constants.length == 0) {
            return null;
        }
        ArrayNode values = context.getGeneratorConfig().createArrayNode();
        Set<String> types = new LinkedHashSet<>();
        for (Object constant : constants) {
            JsonNode value;
            try {
                value = ENUM_VALUE_MAPPER.valueToTree(constant);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (value.isContainerNode()) {
                // Handle only scalar values here. Object and array values fall back to the
                // existing enum handling; reading them back may require a @JsonCreator.
                return null;
            }
            if ((value.isFloat() || value.isDouble()) && !Double.isFinite(value.doubleValue())) {
                // JSON has no non-finite numbers; Jackson writes these as strings such as "NaN".
                value = TextNode.valueOf(value.asText());
            }
            values.add(value);
            types.add(jsonType(value, context));
        }
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        if (types.size() == 1) {
            definition.put(context.getKeyword(SchemaKeyword.TAG_TYPE), types.iterator().next());
        } else {
            ArrayNode typeArray = definition.putArray(context.getKeyword(SchemaKeyword.TAG_TYPE));
            types.forEach(typeArray::add);
        }
        if (values.size() == 1) {
            definition.set(context.getKeyword(SchemaKeyword.TAG_CONST), values.get(0));
        } else {
            definition.set(context.getKeyword(SchemaKeyword.TAG_ENUM), values);
        }
        return new CustomDefinition(definition);
    }

    private static String jsonType(JsonNode value, SchemaGenerationContext context) {
        if (value.isIntegralNumber()) {
            return context.getKeyword(SchemaKeyword.TAG_TYPE_INTEGER);
        } else if (value.isNumber()) {
            return context.getKeyword(SchemaKeyword.TAG_TYPE_NUMBER);
        } else if (value.isBoolean()) {
            return context.getKeyword(SchemaKeyword.TAG_TYPE_BOOLEAN);
        } else if (value.isNull()) {
            return context.getKeyword(SchemaKeyword.TAG_TYPE_NULL);
        }
        return context.getKeyword(SchemaKeyword.TAG_TYPE_STRING);
    }
}
