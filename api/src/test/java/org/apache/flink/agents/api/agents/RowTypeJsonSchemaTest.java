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

import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link RowTypeJsonSchema}. */
class RowTypeJsonSchemaTest {

    /** The same row's schema is asserted in the Python test_render_row_type_json_schema.py. */
    static final String MIXED_ROW_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                    + "\"count\":{\"type\":\"integer\"},\"score\":{\"type\":\"number\"},"
                    + "\"ok\":{\"type\":\"boolean\"}},\"required\":[\"name\",\"count\",\"score\","
                    + "\"ok\"],\"additionalProperties\":false}";

    /** The same row's schema is asserted in the Python test_render_row_type_json_schema.py. */
    static final String UNNAMED_ROW_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"f0\":{\"type\":\"string\"},"
                    + "\"f1\":{\"type\":\"integer\"}},\"required\":[\"f0\",\"f1\"],"
                    + "\"additionalProperties\":false}";

    /** The same row's schema is asserted in the Python test_render_row_type_json_schema.py. */
    static final String ESCAPED_ROW_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"a\\\"b\":{\"type\":\"string\"},"
                    + "\"c\\\\d\":{\"type\":\"string\"},\"e\\u000bf\":{\"type\":\"string\"},"
                    + "\"g\\u001fh\":{\"type\":\"string\"},\"i\\nj\":{\"type\":\"string\"},"
                    + "\"k/l\":{\"type\":\"string\"},\"\uC810\uC218\":{\"type\":\"string\"},"
                    + "\"m\u2028n\":{\"type\":\"string\"},\"\uD83D\uDE00\":{\"type\":\"string\"}},"
                    + "\"required\":[\"a\\\"b\",\"c\\\\d\",\"e\\u000bf\",\"g\\u001fh\","
                    + "\"i\\nj\",\"k/l\",\"\uC810\uC218\",\"m\u2028n\",\"\uD83D\uDE00\"],"
                    + "\"additionalProperties\":false}";

    @Test
    @DisplayName("A flat row renders as an object schema with every field required, in field order")
    void rendersFlatRowInFieldOrder() {
        RowTypeInfo row =
                new RowTypeInfo(
                        new TypeInformation[] {
                            BasicTypeInfo.STRING_TYPE_INFO,
                            BasicTypeInfo.INT_TYPE_INFO,
                            BasicTypeInfo.DOUBLE_TYPE_INFO,
                            BasicTypeInfo.BOOLEAN_TYPE_INFO
                        },
                        new String[] {"name", "count", "score", "ok"});

        assertThat(RowTypeJsonSchema.render(row)).isEqualTo(MIXED_ROW_SCHEMA);
    }

    @Test
    @DisplayName("Integral and decimal basic types map to integer and number")
    void mapsNumericTypes() {
        RowTypeInfo row =
                new RowTypeInfo(
                        new TypeInformation[] {
                            BasicTypeInfo.BYTE_TYPE_INFO,
                            BasicTypeInfo.SHORT_TYPE_INFO,
                            BasicTypeInfo.LONG_TYPE_INFO,
                            BasicTypeInfo.BIG_INT_TYPE_INFO,
                            BasicTypeInfo.FLOAT_TYPE_INFO,
                            BasicTypeInfo.BIG_DEC_TYPE_INFO
                        },
                        new String[] {"b", "s", "l", "bi", "f", "bd"});

        assertThat(RowTypeJsonSchema.render(row))
                .isEqualTo(
                        "{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"integer\"},"
                                + "\"s\":{\"type\":\"integer\"},\"l\":{\"type\":\"integer\"},"
                                + "\"bi\":{\"type\":\"integer\"},\"f\":{\"type\":\"number\"},"
                                + "\"bd\":{\"type\":\"number\"}},\"required\":[\"b\",\"s\",\"l\","
                                + "\"bi\",\"f\",\"bd\"],\"additionalProperties\":false}");
    }

    @Test
    @DisplayName("A field with no supported mapping leaves the row unrendered")
    void returnsNullForFieldsWithoutJsonType() {
        for (TypeInformation<?> type :
                new TypeInformation<?>[] {
                    BasicTypeInfo.CHAR_TYPE_INFO,
                    BasicTypeInfo.DATE_TYPE_INFO,
                    BasicTypeInfo.INSTANT_TYPE_INFO,
                    BasicTypeInfo.VOID_TYPE_INFO
                }) {
            RowTypeInfo row =
                    new RowTypeInfo(
                            new TypeInformation[] {BasicTypeInfo.STRING_TYPE_INFO, type},
                            new String[] {"name", "other"});

            assertThat(RowTypeJsonSchema.render(row)).as(type.toString()).isNull();
        }
    }

    @Test
    @DisplayName("Escaped field names render the same text as in Python")
    void escapesFieldNamesLikePython() {
        String[] names = {
            "a\"b",
            "c\\d",
            "e\u000Bf",
            "g\u001Fh",
            "i\nj",
            "k/l",
            "\uC810\uC218",
            "m\u2028n",
            "\uD83D\uDE00"
        };
        TypeInformation<?>[] types = new TypeInformation<?>[names.length];
        Arrays.fill(types, BasicTypeInfo.STRING_TYPE_INFO);

        assertThat(RowTypeJsonSchema.render(new RowTypeInfo(types, names)))
                .isEqualTo(ESCAPED_ROW_SCHEMA);
    }

    @Test
    @DisplayName("A row built without names renders the default f0, f1, ... names")
    void rendersDefaultFieldNames() {
        RowTypeInfo row =
                new RowTypeInfo(BasicTypeInfo.STRING_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO);

        assertThat(RowTypeJsonSchema.render(row)).isEqualTo(UNNAMED_ROW_SCHEMA);
    }
}
