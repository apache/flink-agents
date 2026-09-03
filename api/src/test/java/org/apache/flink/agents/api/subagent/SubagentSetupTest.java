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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.context.RunnerContext;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

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

        public String getPath() {
            return path;
        }

        public int getLines() {
            return lines;
        }
    }

    /** Reaches itself through its own member, so rendering it as a schema does not terminate. */
    public static class Cyclic {
        private Cyclic next;

        public Cyclic getNext() {
            return next;
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

    @Test
    void anInputTypeIsRenderedAsTheInputSchema() throws Exception {
        TypedSetup setup = new TypedSetup(Review.class, Object.class);

        JsonNode schema = MAPPER.readTree(setup.getInputSchema());
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("path").path("type").asText())
                .isEqualTo("string");
        assertThat(schema.path("properties").path("lines").path("type").asText())
                .isEqualTo("integer");
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
}
