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
import java.util.List;

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
