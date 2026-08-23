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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

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
