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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.Option;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link PojoJsonSchemaGenerator}. */
class PojoJsonSchemaGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Output schema fixture shaped to expose the generation settings: fields declared in a
     * non-alphabetical order, one {@code Optional} field, and a getter with no backing field.
     */
    public static class Report {
        public String summary;
        public Map<String, Integer> counts;
        public Optional<String> note;
        public int total;

        public String getDerived() {
            return summary + total;
        }
    }

    /**
     * Output schema fixture shaped to expose Jackson's property model.
     *
     * <p>{@code name} is deserialized from {@code full_name} rather than from the Java field name,
     * and {@code secret} is not deserialized at all.
     */
    public static class Profile {
        @JsonProperty("full_name")
        public String name;

        @JsonIgnore public String secret;

        public int age;
    }

    /**
     * Output schema fixture whose enum constants are deserialized from values other than their
     * names, one through {@code @JsonProperty} on the constants and one through a
     * {@code @JsonValue} method.
     */
    public static class Ticket {
        public Status status;

        public Phase phase;
    }

    public enum Status {
        @JsonProperty("in-progress")
        IN_PROGRESS,
        @JsonProperty("done")
        DONE
    }

    public enum Phase {
        STARTED("started"),
        FINISHED("finished");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    @Test
    @DisplayName("The generated schema names properties the way Jackson deserializes them")
    void namesPropertiesTheWayJacksonReadsThem() {
        JsonNode schema = PojoJsonSchemaGenerator.generate(Profile.class);

        // A caller deserializing the response into this class accepts the renamed property and
        // rejects the Java field name, and discards an ignored property that the schema would
        // otherwise state as required and so force a value for.
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrder("full_name", "age");
    }

    @Test
    @DisplayName("The generated schema lists enum constants the way Jackson deserializes them")
    void listsEnumConstantsByTheirJacksonWireValues() throws Exception {
        JsonNode properties = PojoJsonSchemaGenerator.generate(Ticket.class).path("properties");

        // Every listed value is one the model may emit, so each has to deserialize into the enum.
        // Listed by constant name instead, a plain mapper refuses every value the schema allows.
        List<Status> statuses = new ArrayList<>();
        for (JsonNode value : properties.path("status").path("enum")) {
            statuses.add(MAPPER.treeToValue(value, Status.class));
        }
        assertThat(statuses).containsExactlyInAnyOrder(Status.values());

        List<Phase> phases = new ArrayList<>();
        for (JsonNode value : properties.path("phase").path("enum")) {
            phases.add(MAPPER.treeToValue(value, Phase.class));
        }
        assertThat(phases).containsExactlyInAnyOrder(Phase.values());
    }

    @Test
    @DisplayName("The generated schema requires every field the caller did not make optional")
    void requiresEveryFieldExceptOptional() {
        JsonNode schema = PojoJsonSchemaGenerator.generate(Report.class);

        // Without a required set the model may omit fields the caller declared, and with an
        // all-inclusive one it must invent a value for the field the caller made omissible.
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("summary", "counts", "total");
    }

    @Test
    @DisplayName("The generated schema keeps declaration order and emits no getter property")
    void keepsDeclarationOrderWithoutGetters() {
        JsonNode schema = PojoJsonSchemaGenerator.generate(Report.class);

        // The fixture declares its fields out of alphabetical order, so an alphabetical sort
        // reorders them, and a getter surfacing as a property adds an entry.
        assertThat(fieldNames(schema.path("properties")))
                .containsExactly("summary", "counts", "note", "total");
    }

    @Test
    @DisplayName("The generated schema declares JSON Schema draft 2020-12")
    void declaresDraft202012() {
        JsonNode schema = PojoJsonSchemaGenerator.generate(Report.class);

        assertThat(schema.path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    @DisplayName("The generated schema applies an option only when it is passed in")
    void appliesAnOptionOnlyWhenPassedIn() {
        JsonNode withOption =
                PojoJsonSchemaGenerator.generate(
                                Report.class, Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                        .path("properties")
                        .path("counts");
        JsonNode withoutOption =
                PojoJsonSchemaGenerator.generate(Report.class).path("properties").path("counts");

        // The map's value schema comes from the option: typed when it is passed, absent when not.
        assertThat(withOption.path("additionalProperties").path("type").asText())
                .isEqualTo("integer");
        assertThat(withoutOption.has("additionalProperties")).isFalse();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
