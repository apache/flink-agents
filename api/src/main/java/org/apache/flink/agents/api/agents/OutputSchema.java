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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Helper class for {@link RowTypeInfo} serialization.
 *
 * <p>Currently, only support row contains basic type.
 */
@VisibleForTesting
@JsonSerialize(using = OutputSchema.OutputSchemaJsonSerializer.class)
@JsonDeserialize(using = OutputSchema.OutputSchemaJsonDeserializer.class)
public class OutputSchema {
    private final RowTypeInfo schema;

    public OutputSchema(RowTypeInfo schema) {
        this.schema = schema;
        for (TypeInformation<?> info : schema.getFieldTypes()) {
            if (!info.isBasicType()) {
                throw new IllegalArgumentException(
                        "Currently, output schema only support row contains basic type.");
            }
        }
    }

    public RowTypeInfo getSchema() {
        return schema;
    }

    /**
     * Rejects a rendered JSON Schema that cannot constrain a response.
     *
     * <p>An object declaring {@code properties} that is present and empty admits every response and
     * rejects none, so a chat model given such a schema returns text that conforms to nothing. That
     * happens whenever a member has a type carrying no serializable state, such as a functional
     * interface. An object whose {@code properties} is <em>absent</em> is a different shape: it is
     * how a free-form map such as {@code Map<String, String>} renders, which is a legitimate
     * constraint and is accepted. Only the present-and-empty form is refused.
     *
     * <p>The walk descends through {@code properties} values and {@code items}, so a member that
     * constrains nothing is found however deeply it is nested. {@code items} is descended even
     * though the generator used here only ever puts a primitive schema there, because a subschema
     * under {@code items} is universal across JSON Schema drafts and a document produced by any
     * other generator will carry one. The path reported for such a subschema names the array
     * member, since neither this walk nor its Python counterpart extends the path across {@code
     * items}.
     *
     * <p>A class exposing its state through {@code @JsonAnyGetter} is a known false positive: it
     * renders byte-identically to a class whose only member cannot be rendered, so no inspection of
     * the rendered document can separate the two.
     *
     * <p>A member whose declared type is a {@code @JsonTypeInfo} base that carries no fields of its
     * own is refused. The generator renders the declared type rather than the subtypes, so such a
     * member yields an object with no properties and genuinely constrains nothing. Declare the
     * member as a concrete type, or give the base the fields every subtype shares.
     *
     * @param schema the rendered JSON Schema to inspect.
     * @param schemaName the name of the schema, quoted in the error to identify the offending
     *     class.
     * @throws IllegalArgumentException if any object at or below {@code schema} carries a {@code
     *     properties} member that is present and empty.
     */
    public static void rejectUnconstrainedSchema(JsonNode schema, String schemaName) {
        rejectEmptyObjects(schema, "$", schemaName);
    }

    /**
     * Raises if any object at or below {@code node} declares an empty {@code properties}.
     *
     * <p>The walk carries no visited set, because a Jackson-generated schema cannot contain a
     * cycle: a self-referential class exhausts the stack inside the generator before any node is
     * returned, and the generator emits a fresh node per member rather than sharing one. The
     * document is therefore a finite tree and a cycle guard would never fire.
     */
    private static void rejectEmptyObjects(JsonNode node, String path, String schemaName) {
        if (node == null || !node.isObject()) {
            return;
        }

        JsonNode properties = node.get("properties");
        boolean declaresNoProperties =
                properties != null && properties.isObject() && properties.size() == 0;
        if ("object".equals(node.path("type").asText()) && declaresNoProperties) {
            throw new IllegalArgumentException(
                    String.format(
                            "Output schema %s renders to a JSON Schema that cannot constrain the"
                                    + " response: the object at path %s has no properties. Use a schema"
                                    + " whose objects each declare at least one field, or pass no output"
                                    + " schema.",
                            schemaName, path));
        }

        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> members = properties.fields();
            while (members.hasNext()) {
                Map.Entry<String, JsonNode> member = members.next();
                rejectEmptyObjects(member.getValue(), path + "." + member.getKey(), schemaName);
            }
        }
        rejectEmptyObjects(node.get("items"), path, schemaName);
    }

    public static class OutputSchemaJsonSerializer extends StdSerializer<OutputSchema> {

        protected OutputSchemaJsonSerializer() {
            super(OutputSchema.class);
        }

        @Override
        public void serialize(
                OutputSchema schema,
                JsonGenerator jsonGenerator,
                SerializerProvider serializerProvider)
                throws IOException {
            RowTypeInfo typeInfo = schema.getSchema();
            jsonGenerator.writeStartObject();

            jsonGenerator.writeFieldName("fieldNames");
            jsonGenerator.writeStartArray();
            for (String name : typeInfo.getFieldNames()) {
                jsonGenerator.writeString(name);
            }
            jsonGenerator.writeEndArray();

            // TODO: support type information which is not basic.
            jsonGenerator.writeFieldName("types");
            jsonGenerator.writeStartArray();
            for (TypeInformation<?> info : typeInfo.getFieldTypes()) {
                jsonGenerator.writeObject(info.getTypeClass());
            }
            jsonGenerator.writeEndArray();

            jsonGenerator.writeEndObject();
        }
    }

    public static class OutputSchemaJsonDeserializer extends StdDeserializer<OutputSchema> {
        private static final ObjectMapper mapper = new ObjectMapper();

        protected OutputSchemaJsonDeserializer() {
            super(OutputSchema.class);
        }

        @Override
        public OutputSchema deserialize(
                JsonParser jsonParser, DeserializationContext deserializationContext)
                throws IOException, JacksonException {
            JsonNode node = jsonParser.getCodec().readTree(jsonParser);
            List<String> fieldNames = new ArrayList<>();
            node.get("fieldNames").forEach(fieldNameNode -> fieldNames.add(fieldNameNode.asText()));
            List<TypeInformation<?>> types = new ArrayList<>();
            node.get("types")
                    .forEach(
                            typeNode -> {
                                try {
                                    types.add(
                                            BasicTypeInfo.getInfoFor(
                                                    mapper.treeToValue(typeNode, Class.class)));
                                } catch (JsonProcessingException e) {
                                    throw new RuntimeException(e);
                                }
                            });

            return new OutputSchema(
                    new RowTypeInfo(
                            types.toArray(new TypeInformation[0]),
                            fieldNames.toArray(new String[0])));
        }
    }
}
