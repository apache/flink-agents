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

package org.apache.flink.agents.api.agents;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ReActAgentTest {
    @Test
    public void testOutputSchemaSerialization() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        RowTypeInfo typeInfo =
                new RowTypeInfo(
                        new TypeInformation[] {
                            BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO
                        },
                        new String[] {"a", "b"});
        OutputSchema schema = new OutputSchema(typeInfo);
        String json = mapper.writeValueAsString(schema);
        OutputSchema deserialized = mapper.readValue(json, OutputSchema.class);
        Assertions.assertEquals(typeInfo, deserialized.getSchema());
    }

    @Test
    @DisplayName("A member that renders to an object with no properties is rejected by path")
    public void testUnrenderableMemberIsRejected() throws JsonMappingException {
        assertThatThrownBy(
                        () ->
                                OutputSchema.rejectUnconstrainedSchema(
                                        renderSchema(WithCallback.class), "WithCallback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WithCallback")
                .hasMessageContaining("$.callback");
    }

    @Test
    @DisplayName("An unrenderable member nested below the root is still found")
    public void testNestedUnrenderableMemberIsRejected() throws JsonMappingException {
        assertThatThrownBy(
                        () ->
                                OutputSchema.rejectUnconstrainedSchema(
                                        renderSchema(WithNestedCallback.class),
                                        "WithNestedCallback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.inner.callback");
    }

    @Test
    @DisplayName("A root object with no properties is rejected at the root path")
    public void testRootWithoutPropertiesIsRejected() throws JsonProcessingException {
        JsonNode schema = new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{}}");

        assertThatThrownBy(() -> OutputSchema.rejectUnconstrainedSchema(schema, "FieldLess"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FieldLess")
                .hasMessageContaining("at path $ has no properties");
    }

    @Test
    @DisplayName("A free-form map member is accepted because its properties are absent, not empty")
    public void testMapMemberIsAccepted() throws JsonMappingException {
        JsonNode schema = renderSchema(WithMap.class);

        assertThatCode(() -> OutputSchema.rejectUnconstrainedSchema(schema, "WithMap"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A schema whose members all render is accepted")
    public void testRenderableSchemaIsAccepted() throws JsonMappingException {
        JsonNode schema = renderSchema(WithCount.class);

        assertThatCode(() -> OutputSchema.rejectUnconstrainedSchema(schema, "WithCount"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An empty properties member on a node that is not an object is left alone")
    public void testNonObjectWithEmptyPropertiesIsAccepted() throws JsonProcessingException {
        JsonNode schema = new ObjectMapper().readTree("{\"type\":\"array\",\"properties\":{}}");

        assertThatCode(() -> OutputSchema.rejectUnconstrainedSchema(schema, "ArrayNode"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An array whose item schema constrains nothing is rejected")
    public void testUnconstrainedItemSchemaIsRejected() throws JsonProcessingException {
        JsonNode schema =
                new ObjectMapper()
                        .readTree(
                                "{\"type\":\"object\",\"properties\":{\"rows\":{\"type\":\"array\","
                                        + "\"items\":{\"type\":\"object\",\"properties\":{}}}}}");

        assertThatThrownBy(() -> OutputSchema.rejectUnconstrainedSchema(schema, "WithRows"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WithRows")
                .hasMessageContaining("at path $.rows has no properties");
    }

    @Test
    @DisplayName("An agent built on an unrenderable schema names the offending path, unwrapped")
    public void testAgentRejectsUnrenderableSchemaByPath() {
        // The path is what identifies the offending member, so it has to survive to the message the
        // caller reads. A catch that also covers the check would bury it one getCause() down.
        assertThatThrownBy(() -> agentWithSchema(WithNestedCallback.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WithNestedCallback")
                .hasMessageContaining("$.inner.callback")
                // No cause: a schema that renders but constrains nothing is not a render
                // failure, and a wrapper over the check would relabel it as one.
                .hasNoCause();
    }

    @Test
    @DisplayName("An agent built on a schema Jackson cannot render reports it with the cause kept")
    public void testAgentRejectsSchemaThatCannotRender() {
        assertThatThrownBy(() -> agentWithSchema(FieldLess.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FieldLess")
                .hasMessageContaining("cannot be rendered as a JSON Schema")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An agent built on a free-form map member still prompts with the full schema")
    public void testAgentAcceptsMapMemberSchema() {
        assertThat(schemaPromptOf(agentWithSchema(WithMap.class)))
                .contains("\"count\":{\"type\":\"integer\"}")
                .contains("\"entries\":{\"type\":\"object\"}");
    }

    @Test
    @DisplayName("An agent built on a renderable schema prompts with the schema it always did")
    public void testAgentAcceptsRenderableSchema() {
        assertThat(schemaPromptOf(agentWithSchema(WithCount.class)))
                .contains(
                        "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}");
    }

    @Test
    @DisplayName("An agent built on a polymorphic member with a field-less base is rejected")
    public void testAgentRejectsPolymorphicMemberWithFieldLessBase() {
        // The generator renders the declared type rather than its subtypes, so a base declaring no
        // fields of its own reaches the prompt as an object that constrains nothing. Giving the
        // base a single shared field flips this to accepted, so it is the field-less form that is
        // pinned here.
        assertThatThrownBy(() -> agentWithSchema(Owner.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner")
                .hasMessageContaining("$.pet");
    }

    private static ReActAgent agentWithSchema(Class<?> outputSchema) {
        return new ReActAgent(
                ResourceDescriptor.Builder.newBuilder("com.example.ChatModel").build(),
                null,
                outputSchema);
    }

    private static String schemaPromptOf(ReActAgent agent) {
        Prompt schemaPrompt =
                (Prompt)
                        agent.getResources().get(ResourceType.PROMPT).get("_default_schema_prompt");
        return schemaPrompt.formatString(Map.of());
    }

    /** A polymorphic base declaring no fields of its own, so it renders to an empty object. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Dog.class, name = "dog"),
        @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    public abstract static class Pet {}

    /** One arm of the {@link Pet} union. */
    public static class Dog extends Pet {
        public String bark;
    }

    /** The other arm of the {@link Pet} union. */
    public static class Cat extends Pet {
        public String meow;
    }

    /** Holds a polymorphic member whose base declares no fields. */
    public static class Owner {
        public String name;
        public Pet pet;
    }

    /** A class with no members at all, which Jackson refuses to render rather than rendering. */
    public static class FieldLess {}

    private static JsonNode renderSchema(Class<?> pojo) throws JsonMappingException {
        return new ObjectMapper().generateJsonSchema(pojo).getSchemaNode();
    }

    /** A member whose type carries no serializable state, so it renders to an empty object. */
    public static class WithCallback {
        public int count;
        public Function<String, String> callback;
    }

    /** Holds {@link WithCallback} one level down, out of reach of a root-only check. */
    public static class WithNestedCallback {
        public int count;
        public WithCallback inner;
    }

    /** A free-form map, which renders without a {@code properties} member at all. */
    public static class WithMap {
        public int count;
        public Map<String, String> entries;
    }

    /** A member that renders to a concrete type. */
    public static class WithCount {
        public int count;
    }
}
