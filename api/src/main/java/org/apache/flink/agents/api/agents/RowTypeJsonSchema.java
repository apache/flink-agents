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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;

import javax.annotation.Nullable;

/**
 * Renders a flat {@link RowTypeInfo} as a JSON Schema object, so a row output schema reaches the
 * model as JSON Schema rather than as the type's string form.
 *
 * <p>The Python {@code render_row_type_json_schema} produces the same text for the same row, so
 * both languages describe a row to the model identically.
 */
@Internal
public final class RowTypeJsonSchema {

    /** Writes lowercase hex digits in escapes, as Python's {@code json.dumps} does. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper(
                    JsonFactory.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build());

    private RowTypeJsonSchema() {}

    /**
     * Returns the JSON Schema of {@code rowType}: every field required, with no additional
     * properties.
     *
     * @return the schema as compact JSON, or {@code null} when a field has no supported mapping in
     *     this renderer, such as a char, date or instant field, which the caller then describes
     *     another way.
     */
    @Nullable
    public static String render(RowTypeInfo rowType) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        String[] names = rowType.getFieldNames();
        for (int i = 0; i < names.length; i++) {
            String jsonType = jsonType(rowType.getTypeAt(i));
            if (jsonType == null) {
                return null;
            }
            properties.putObject(names[i]).put("type", jsonType);
            required.add(names[i]);
        }
        schema.set("required", required);
        schema.put("additionalProperties", false);
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private static String jsonType(TypeInformation<?> type) {
        if (BasicTypeInfo.STRING_TYPE_INFO.equals(type)) {
            return "string";
        } else if (BasicTypeInfo.BOOLEAN_TYPE_INFO.equals(type)) {
            return "boolean";
        } else if (BasicTypeInfo.BYTE_TYPE_INFO.equals(type)
                || BasicTypeInfo.SHORT_TYPE_INFO.equals(type)
                || BasicTypeInfo.INT_TYPE_INFO.equals(type)
                || BasicTypeInfo.LONG_TYPE_INFO.equals(type)
                || BasicTypeInfo.BIG_INT_TYPE_INFO.equals(type)) {
            return "integer";
        } else if (BasicTypeInfo.FLOAT_TYPE_INFO.equals(type)
                || BasicTypeInfo.DOUBLE_TYPE_INFO.equals(type)
                || BasicTypeInfo.BIG_DEC_TYPE_INFO.equals(type)) {
            return "number";
        }
        return null;
    }
}
