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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

    /**
     * Output schema fixture for enums the Jackson module's flattened enums misstate: one maps only
     * some of its constants through {@code @JsonProperty}, and one maps its constants to numbers
     * through a {@code @JsonValue} method.
     */
    public static class Order {
        public PartlyMapped status;
        public Sign sign;
    }

    public enum PartlyMapped {
        @JsonProperty("in-progress")
        IN_PROGRESS,
        DONE
    }

    public enum Sign {
        POSITIVE(1),
        NEGATIVE(-1);

        private final int value;

        Sign(int value) {
            this.value = value;
        }

        @JsonValue
        public int value() {
            return value;
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
    @DisplayName("The generated schema lists a partly annotated enum the way Jackson reads it")
    void listsPartlyAnnotatedEnumByItsJacksonWireValues() throws Exception {
        JsonNode status =
                PojoJsonSchemaGenerator.generate(Order.class).path("properties").path("status");

        // Listing the Java name of the annotated constant would let the model emit a value that
        // fails to deserialize, so the schema has to name it by its @JsonProperty value.
        assertThat(textValues(status.path("enum"))).containsExactly("in-progress", "DONE");
        for (JsonNode value : status.path("enum")) {
            MAPPER.treeToValue(value, PartlyMapped.class);
        }
    }

    @Test
    @DisplayName("The enum definition takes precedence over the Jackson module's enum handling")
    void enumDefinitionWinsOverJacksonModule() throws Exception {
        JsonNode sign =
                PojoJsonSchemaGenerator.generate(Order.class).path("properties").path("sign");

        // The Jackson module also handles a @JsonValue enum, typing it as a string even when the
        // values are numbers, so a numeric type here comes from the generator's own definition.
        assertThat(sign.toString()).isEqualTo("{\"type\":\"integer\",\"enum\":[1,-1]}");
        List<Sign> signs = new ArrayList<>();
        for (JsonNode value : sign.path("enum")) {
            signs.add(MAPPER.treeToValue(value, Sign.class));
        }
        assertThat(signs).containsExactly(Sign.POSITIVE, Sign.NEGATIVE);
    }

    /** Output schema fixture for enums whose written values need care in a schema. */
    public static class Measures {
        public Limit limit;
        public Only only;
        public Huge huge;
    }

    public enum Limit {
        FINITE(1.5),
        UNBOUNDED(Double.POSITIVE_INFINITY),
        UNDEFINED(Double.NaN);

        private final double value;

        Limit(double value) {
            this.value = value;
        }

        @JsonValue
        public double value() {
            return value;
        }
    }

    public enum Huge {
        BEYOND_DOUBLE(new BigDecimal("1E400")),
        ONE(BigDecimal.ONE);

        private final BigDecimal value;

        Huge(BigDecimal value) {
            this.value = value;
        }

        @JsonValue
        public BigDecimal value() {
            return value;
        }
    }

    public enum Only {
        @JsonProperty("only")
        ONLY
    }

    @Test
    @DisplayName("The enum definition writes non-finite numbers as strings and one value as const")
    void writesNonFiniteNumbersAsStringsAndSingleValuesAsConst() {
        for (ObjectNode schema :
                List.of(
                        PojoJsonSchemaGenerator.generate(Measures.class),
                        PojoJsonSchemaGenerator.generateAllRequired(Measures.class))) {
            JsonNode properties = schema.path("properties");

            // JSON has no NaN or Infinity, so Jackson writes them as strings, and the schema has
            // to list them the same way; a single value keeps the const form victools uses.
            assertThat(properties.path("limit").toString())
                    .isEqualTo(
                            "{\"type\":[\"number\",\"string\"],\"enum\":[1.5,\"Infinity\",\"NaN\"]}");
            assertThat(properties.path("only").toString())
                    .isEqualTo("{\"type\":\"string\",\"const\":\"only\"}");
            // A finite decimal beyond double's range is still written as a number.
            assertThat(properties.path("huge").path("type").asText()).isEqualTo("number");
            assertThat(properties.path("huge").path("enum").get(0).isNumber()).isTrue();
        }
    }

    /** Output schema fixture holding an enum that declares no constants. */
    public static class Placeholder {
        public Optional<Empty> empty;
    }

    public enum Empty {}

    @Test
    @DisplayName("An enum without constants keeps the schema it had before")
    void leavesEnumWithoutConstantsToJacksonModule() {
        // An enum definition built from no values would declare an empty type list, which no
        // instance satisfies, so even the null the Optional field allows would be rejected.
        for (ObjectNode schema :
                List.of(
                        PojoJsonSchemaGenerator.generate(Placeholder.class),
                        PojoJsonSchemaGenerator.generateAllRequired(Placeholder.class))) {
            JsonNode empty = schema.path("properties").path("empty");
            assertThat(empty.toString()).doesNotContain("\"type\":[]");
            assertThat(textValues(empty.path("type"))).contains("null");
        }
    }

    /** Output schema fixture holding an enum that Jackson writes as an object. */
    public static class Shipment {
        public Carrier carrier;
    }

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    public enum Carrier {
        GROUND(1),
        AIR(2);

        public final int code;

        Carrier(int code) {
            this.code = code;
        }
    }

    @Test
    @DisplayName("An enum written as an object keeps its constant names in the schema")
    void listsObjectShapedEnumByItsConstantNames() throws Exception {
        // Listed as objects, the values would sit under "type":"object" with no declared
        // properties, which a strict schema rejects, and a plain mapper cannot read them back.
        JsonNode carrier =
                PojoJsonSchemaGenerator.generateAllRequired(
                                Shipment.class, Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                        .path("properties")
                        .path("carrier");
        assertThat(carrier.path("type").asText()).isEqualTo("string");
        List<Carrier> carriers = new ArrayList<>();
        for (JsonNode value : carrier.path("enum")) {
            carriers.add(MAPPER.treeToValue(value, Carrier.class));
        }
        assertThat(carriers).containsExactly(Carrier.GROUND, Carrier.AIR);
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
    @DisplayName("The all-required schema requires an Optional field and admits null for it")
    void allRequiredRequiresOptionalFieldsAsNullable() {
        JsonNode schema = PojoJsonSchemaGenerator.generateAllRequired(Report.class);

        // A provider expecting every property in the required set rejects a schema that leaves
        // the Optional field out, so the field stays omissible only by taking null.
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("summary", "counts", "note", "total");
        assertThat(textValues(schema.path("properties").path("note").path("type")))
                .contains("null");
    }

    @Test
    @DisplayName("The all-required schema differs from the default one only in its required set")
    void allRequiredChangesOnlyTheRequiredSet() {
        for (Class<?> type : List.of(Report.class, Profile.class, Ticket.class, Order.class)) {
            ObjectNode allRequired =
                    PojoJsonSchemaGenerator.generateAllRequired(
                            type, Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
            ObjectNode partlyRequired =
                    PojoJsonSchemaGenerator.generate(
                            type, Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
            allRequired.remove("required");
            partlyRequired.remove("required");

            // Compared as text, so property order, enum values and the option count too.
            assertThat(allRequired.toString()).isEqualTo(partlyRequired.toString());
        }
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
