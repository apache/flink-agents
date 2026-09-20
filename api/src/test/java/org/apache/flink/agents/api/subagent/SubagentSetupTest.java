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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.context.RunnerContext;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins the routing metadata {@link SubagentSetup} carries for a caller. */
public class SubagentSetupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A setup that only carries metadata: invocation lives in the runtime layer. */
    private static class MetadataOnlySetup extends SubagentSetup {

        private static final long serialVersionUID = 1L;

        MetadataOnlySetup() {
            super();
        }

        MetadataOnlySetup(String description) {
            super(description);
        }

        MetadataOnlySetup(String description, @Nullable String inputSchema) {
            super(description, inputSchema);
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Types what it takes and what it returns instead of spelling out a schema. */
    private static class TypedSetup extends MetadataOnlySetup {

        private static final long serialVersionUID = 1L;

        private final Class<?> inputType;
        private final Class<?> resultType;

        TypedSetup(Class<?> inputType, Class<?> resultType) {
            super("Reviews a file.");
            this.inputType = inputType;
            this.resultType = resultType;
        }

        @Override
        public Class<?> getInputType() {
            return inputType;
        }

        @Override
        public Class<?> getResultType() {
            return resultType;
        }
    }

    /** The arguments of the typed setups above. */
    public static class Review {
        private String path;
        private int lines;
        private byte[] payload;

        public String getPath() {
            return path;
        }

        public int getLines() {
            return lines;
        }

        public byte[] getPayload() {
            return payload;
        }
    }

    /** Reaches itself through its own member, so rendering it as a schema does not terminate. */
    public static class Cyclic {
        private Cyclic next;

        public Cyclic getNext() {
            return next;
        }
    }

    /** A value object one level down, so alignment has to recurse to reach it. */
    public static class Nested {
        private String name;
        private int count;
        private byte[] blob;

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }

        public byte[] getBlob() {
            return blob;
        }
    }

    /**
     * Holds a {@link Nested} object, whose own required list and {@code byte[]} sit one level in.
     */
    public static class WithNested {
        private String id;
        private Nested nested;

        public String getId() {
            return id;
        }

        public Nested getNested() {
            return nested;
        }
    }

    /**
     * Properties whose name in the schema is not the Java field name: a {@code boolean isActive}
     * the schema calls {@code active}, a getter renamed with {@link JsonProperty}, and a getter
     * with no field behind it. All three are primitives, so all three are optional; matching on the
     * field name finds none of them and wrongly marks each one required.
     */
    public static class Naming {
        private boolean isActive;
        private String label;

        public boolean isActive() {
            return isActive;
        }

        public String getLabel() {
            return label;
        }

        @JsonProperty("renamed")
        public int getCount() {
            return 0;
        }

        public boolean isReady() {
            return true;
        }
    }

    @Test
    void aSetupThatDeclaresNothingStatesNoShapeForItsArguments() {
        MetadataOnlySetup setup = new MetadataOnlySetup();

        assertThat(setup.getDescription()).isEmpty();
        assertThat(setup.getInputType()).isEqualTo(Object.class);
        assertThat(setup.getResultType()).isEqualTo(Object.class);
        assertThat(setup.getInputSchema()).isNull();
    }

    @Test
    void aDescriptionAloneStillStatesNoInputShape() {
        MetadataOnlySetup setup = new MetadataOnlySetup("Reviews a changed file.");

        assertThat(setup.getDescription()).isEqualTo("Reviews a changed file.");
        assertThat(setup.getInputSchema()).isNull();
    }

    @Test
    void anAbsentDescriptionReadsAsEmptyRatherThanNull() {
        assertThat(new MetadataOnlySetup(null).getDescription()).isEmpty();
    }

    @Test
    void aBlankInputSchemaIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new MetadataOnlySetup("desc", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input schema must not be blank");
    }

    /** Absent is not blank: it leaves the schema to be derived from the input type. */
    @Test
    void anAbsentInputSchemaIsLeftToTheInputType() {
        assertThat(new MetadataOnlySetup("desc", null).getInputSchema()).isNull();
    }

    /**
     * The derived schema is a cross-language contract, pinned here and in the Python mirror test on
     * which properties a model must send and the JSON type of each. {@link InputSchemas} fills an
     * object-level {@code required} from the non-defaulted properties, which Jackson's legacy
     * generator leaves empty, and rewrites {@code byte[]} to the {@code string}/{@code binary} type
     * pydantic gives a {@code bytes} field, rather than Jackson's array of the non-standard {@code
     * byte} type.
     */
    @Test
    void anInputTypeIsRenderedAsTheInputSchema() throws Exception {
        JsonNode schema =
                MAPPER.readTree(new TypedSetup(Review.class, Object.class).getInputSchema());
        JsonNode properties = schema.path("properties");

        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("path", "payload");
        assertThat(properties.path("path").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("lines").path("type").asText()).isEqualTo("integer");
        assertThat(properties.path("payload").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("payload").path("format").asText()).isEqualTo("binary");
    }

    /**
     * Alignment recurses: a nested object gets the same step as the top level, so it carries its
     * own {@code required} list ({@code name} and {@code blob}, not the primitive {@code count})
     * and its {@code byte[]} is rewritten to {@code string}/{@code binary} rather than left an
     * array of the non-standard {@code byte} type. This is the shape pydantic gives the same model,
     * which the cross-language contract pins.
     */
    @Test
    void aNestedObjectIsAlignedLikeTheTopLevel() throws Exception {
        JsonNode schema =
                MAPPER.readTree(new TypedSetup(WithNested.class, Object.class).getInputSchema());
        JsonNode nested = schema.path("properties").path("nested");

        assertThat(textValues(schema.path("required"))).containsExactlyInAnyOrder("id", "nested");
        assertThat(textValues(nested.path("required"))).containsExactlyInAnyOrder("name", "blob");
        assertThat(nested.path("properties").path("count").path("type").asText())
                .isEqualTo("integer");
        assertThat(nested.path("properties").path("blob").path("type").asText())
                .isEqualTo("string");
        assertThat(nested.path("properties").path("blob").path("format").asText())
                .isEqualTo("binary");
    }

    /**
     * A property is judged optional by whether its Java type is a primitive, matched on the name it
     * carries in the schema. {@code active} (from {@code isActive}), {@code renamed} (a getter with
     * {@link JsonProperty}), and {@code ready} (a getter with no field) are all primitives that a
     * field-name lookup never finds, so only the {@code String label} is required.
     */
    @Test
    void aPrimitiveIsOptionalWhateverItsSchemaName() throws Exception {
        JsonNode schema =
                MAPPER.readTree(new TypedSetup(Naming.class, Object.class).getInputSchema());
        JsonNode properties = schema.path("properties");

        assertThat(textValues(schema.path("required"))).containsExactly("label");
        assertThat(properties.path("active").path("type").asText()).isEqualTo("boolean");
        assertThat(properties.path("renamed").path("type").asText()).isEqualTo("integer");
        assertThat(properties.path("ready").path("type").asText()).isEqualTo("boolean");
    }

    @Test
    void anExplicitInputSchemaWinsOverTheInputType() {
        String declared = "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}";

        assertThat(new MetadataOnlySetup("desc", declared).getInputSchema()).isEqualTo(declared);
    }

    /**
     * The parameters of a callable must be a JSON object, so a type that renders as anything else
     * declares no shape a model could build a call from.
     */
    @Test
    void anInputTypeThatRendersAsNoObjectStatesNoSchema() {
        assertThat(new TypedSetup(String.class, Object.class).getInputSchema()).isNull();
    }

    @Test
    void aSelfReferentialInputTypeIsRejected() {
        assertThatThrownBy(() -> new TypedSetup(Cyclic.class, Object.class).getInputSchema())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is self-referential");
    }

    /** The declared types drive behavior, so they must not leak into the cross-language plan. */
    @Test
    void theDeclaredTypesStayOutOfThePlanJson() throws Exception {
        String json = MAPPER.writeValueAsString(new TypedSetup(Review.class, Review.class));

        assertThat(json).doesNotContain("inputType").doesNotContain("resultType");
    }

    /**
     * The plan JSON is a cross-language contract: these two keys are what the Python side reads, so
     * they are pinned literally rather than through the getters.
     */
    @Test
    void theMetadataSerializesUnderTheCrossLanguageKeys() throws Exception {
        String customSchema =
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

        String json =
                MAPPER.writeValueAsString(new MetadataOnlySetup("Reviews a file.", customSchema));

        assertThat(json)
                .contains("\"description\":\"Reviews a file.\"")
                .contains("\"input_schema\"");
        assertThat(json).doesNotContain("inputSchema");
        Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
        assertThat(parsed).containsEntry("input_schema", customSchema);
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
