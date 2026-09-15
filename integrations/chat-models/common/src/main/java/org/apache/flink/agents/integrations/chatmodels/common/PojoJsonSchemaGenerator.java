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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.generator.impl.PropertySortUtils;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import java.util.Optional;

/** Derives the JSON Schema that chat model connections send as a native output schema. */
public final class PojoJsonSchemaGenerator {

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
    //   - The required check marks every field required except an Optional one. The default marks
    //     nothing required, which lets a model omit fields at will, while marking everything
    //     required would force the fields a caller declared omissible.
    //   - The Jackson module names properties the way an ObjectMapper reads them, honoring
    //     @JsonProperty renames and dropping @JsonIgnore fields, which the required check would
    //     otherwise mark required. It lists enum constants mapped by @JsonProperty or by a
    //     @JsonValue method under those mapped values. A property or enum constant stated under
    //     any other name makes a response that satisfies the schema fail to deserialize. An enum
    //     annotating only some constants falls back to Java names for all of them, so its
    //     annotated constants do not read back. RESPECT_JSONPROPERTY_REQUIRED stays off, so
    //     @JsonProperty(required = true) does not widen the required set configured here.

    /**
     * Generates the JSON Schema for {@code type}.
     *
     * @param type the class a response is deserialized into
     * @param options additional generator options enabled on top of the fixed settings
     * @return the schema document
     */
    public static ObjectNode generate(Class<?> type, Option... options) {
        SchemaGeneratorConfigBuilder builder =
                new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        for (Option option : options) {
            builder.with(option);
        }
        builder.with(
                new JacksonModule(
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE));
        builder.forTypesInGeneral()
                .withPropertySorter(PropertySortUtils.SORT_PROPERTIES_FIELDS_BEFORE_METHODS);
        builder.forFields()
                .withRequiredCheck(field -> !Optional.class.equals(field.getRawMember().getType()));
        return new SchemaGenerator(builder.build()).generateSchema(type);
    }
}
