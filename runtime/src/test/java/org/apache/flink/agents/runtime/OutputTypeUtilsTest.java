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
package org.apache.flink.agents.runtime;

import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link OutputTypeUtils}. */
public class OutputTypeUtilsTest {

    /** A POJO exposing its values through getters only. */
    public static class ReviewOutput {
        private final int score;
        private final String comment;

        public ReviewOutput(int score, String comment) {
            this.score = score;
            this.comment = comment;
        }

        public int getScore() {
            return score;
        }

        public String getComment() {
            return comment;
        }
    }

    /** A POJO exposing its values through public fields only. */
    public static class PublicFields {
        public String name;
        public double value;

        public PublicFields(String name, double value) {
            this.name = name;
            this.value = value;
        }
    }

    private static RowTypeInfo rowType(Schema schema) {
        return OutputTypeUtils.schemaToRowTypeInfo(schema);
    }

    @Test
    void schemaToRowTypeInfoDerivesPhysicalColumnsInOrder() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("name", DataTypes.STRING())
                                .column("score", DataTypes.INT())
                                .column("ratio", DataTypes.DOUBLE())
                                .column("flag", DataTypes.BOOLEAN())
                                .build());

        assertThat(rowType.getFieldNames()).containsExactly("name", "score", "ratio", "flag");
        assertThat(rowType.getTypeAt("name").getTypeClass()).isEqualTo(String.class);
        assertThat(rowType.getTypeAt("score").getTypeClass()).isEqualTo(Integer.class);
        assertThat(rowType.getTypeAt("ratio").getTypeClass()).isEqualTo(Double.class);
        assertThat(rowType.getTypeAt("flag").getTypeClass()).isEqualTo(Boolean.class);
    }

    @Test
    void schemaToRowTypeInfoSkipsComputedColumns() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("id", DataTypes.BIGINT())
                                .columnByExpression("doubled", "id * 2")
                                .build());

        assertThat(rowType.getFieldNames()).containsExactly("id");
        assertThat(rowType.getTypeAt("id").getTypeClass()).isEqualTo(Long.class);
    }

    @Test
    void schemaToRowTypeInfoRejectsSchemaWithoutPhysicalColumns() {
        Schema schema = Schema.newBuilder().columnByExpression("c", "1").build();

        assertThatThrownBy(() -> OutputTypeUtils.schemaToRowTypeInfo(schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no physical columns");
    }

    @Test
    void schemaToRowTypeInfoResolvesSqlStringColumns() {
        // A column declared with a SQL type string is an UnresolvedDataType; it resolves to the
        // same row type as the equivalent DataTypes declaration, for scalars and a nested ROW.
        RowTypeInfo fromSql =
                rowType(
                        Schema.newBuilder()
                                .column("id", "BIGINT")
                                .column("label", "STRING")
                                .column("nested", "ROW<x INT, y STRING>")
                                .build());

        assertThat(fromSql.getFieldNames()).containsExactly("id", "label", "nested");
        assertThat(fromSql.getTypeAt("id").getTypeClass()).isEqualTo(Long.class);
        assertThat(fromSql.getTypeAt("label").getTypeClass()).isEqualTo(String.class);
        assertThat(((RowTypeInfo) fromSql.<Row>getTypeAt("nested")).getFieldNames())
                .containsExactly("x", "y");

        // The SQL strings agree with the equivalent DataTypes object columns.
        RowTypeInfo fromDataTypes =
                rowType(
                        Schema.newBuilder()
                                .column("id", DataTypes.BIGINT())
                                .column("label", DataTypes.STRING())
                                .build());
        RowTypeInfo sqlScalars =
                rowType(
                        Schema.newBuilder()
                                .column("id", "BIGINT")
                                .column("label", "STRING")
                                .build());
        assertThat(sqlScalars.getFieldNames()).containsExactly(fromDataTypes.getFieldNames());
        assertThat(sqlScalars.getTypeAt("id").getTypeClass())
                .isEqualTo(fromDataTypes.getTypeAt("id").getTypeClass());
        assertThat(sqlScalars.getTypeAt("label").getTypeClass())
                .isEqualTo(fromDataTypes.getTypeAt("label").getTypeClass());
    }

    @Test
    void adaptToRowWrapsScalarIntoSingleColumn() {
        RowTypeInfo rowType = rowType(Schema.newBuilder().column("f0", DataTypes.STRING()).build());

        Row row = OutputTypeUtils.adaptToRow("hello", rowType);

        assertThat(row.getArity()).isEqualTo(1);
        assertThat(row.getField(0)).isEqualTo("hello");
    }

    @Test
    void adaptToRowWrapsScalarWhenColumnNameCollidesWithStringInternals() {
        // "value" is String's private byte[] field and "bytes" matches String.getBytes(); a scalar
        // String must be wrapped whole rather than have that internal member extracted, which
        // would otherwise surface as ClassCastException: byte[] cannot be cast to String.
        RowTypeInfo valueColumn =
                rowType(Schema.newBuilder().column("value", DataTypes.STRING()).build());
        assertThat(OutputTypeUtils.adaptToRow("hello", valueColumn).getField(0)).isEqualTo("hello");

        RowTypeInfo bytesColumn =
                rowType(Schema.newBuilder().column("bytes", DataTypes.STRING()).build());
        assertThat(OutputTypeUtils.adaptToRow("hello", bytesColumn).getField(0)).isEqualTo("hello");
    }

    @Test
    void adaptToRowReadsPojoGettersBySchemaName() {
        // Schema column order intentionally differs from the POJO declaration order.
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("comment", DataTypes.STRING())
                                .column("score", DataTypes.INT())
                                .build());

        Row row = OutputTypeUtils.adaptToRow(new ReviewOutput(5, "great"), rowType);

        assertThat(row.getField(0)).isEqualTo("great");
        assertThat(row.getField(1)).isEqualTo(5);
    }

    @Test
    void adaptToRowReadsPublicFieldsWhenNoGetter() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("name", DataTypes.STRING())
                                .column("value", DataTypes.DOUBLE())
                                .build());

        Row row = OutputTypeUtils.adaptToRow(new PublicFields("x", 1.5), rowType);

        assertThat(row.getField(0)).isEqualTo("x");
        assertThat(row.getField(1)).isEqualTo(1.5);
    }

    @Test
    void adaptToRowPrefersPojoPropertyOverScalarForSingleColumn() {
        RowTypeInfo rowType = rowType(Schema.newBuilder().column("score", DataTypes.INT()).build());

        Row row = OutputTypeUtils.adaptToRow(new ReviewOutput(7, "c"), rowType);

        assertThat(row.getField(0)).isEqualTo(7);
    }

    @Test
    void adaptToRowReadsMapBySchemaName() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("a", DataTypes.STRING())
                                .column("b", DataTypes.INT())
                                .build());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("b", 7);
        map.put("a", "v");

        Row row = OutputTypeUtils.adaptToRow(map, rowType);

        assertThat(row.getField(0)).isEqualTo("v");
        assertThat(row.getField(1)).isEqualTo(7);
    }

    @Test
    void adaptToRowPassesThroughPositionalRow() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("a", DataTypes.STRING())
                                .column("b", DataTypes.INT())
                                .build());

        Row row = OutputTypeUtils.adaptToRow(Row.of("v", 7), rowType);

        assertThat(row.getField(0)).isEqualTo("v");
        assertThat(row.getField(1)).isEqualTo(7);
    }

    @Test
    void adaptToRowProjectsNamedRowBySchemaName() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("a", DataTypes.STRING())
                                .column("b", DataTypes.INT())
                                .build());
        Row named = Row.withNames();
        named.setField("b", 7);
        named.setField("a", "v");

        Row row = OutputTypeUtils.adaptToRow(named, rowType);

        assertThat(row.getField(0)).isEqualTo("v");
        assertThat(row.getField(1)).isEqualTo(7);
    }

    @Test
    void adaptToRowRejectsRowWithWrongArity() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("a", DataTypes.STRING())
                                .column("b", DataTypes.INT())
                                .build());

        assertThatThrownBy(() -> OutputTypeUtils.adaptToRow(Row.of("only-one"), rowType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares 2 columns");
    }

    @Test
    void adaptToRowRejectsPojoMissingColumn() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("score", DataTypes.INT())
                                .column("missing", DataTypes.STRING())
                                .build());

        assertThatThrownBy(() -> OutputTypeUtils.adaptToRow(new ReviewOutput(1, "c"), rowType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void adaptToRowRejectsScalarForMultiColumnSchema() {
        RowTypeInfo rowType =
                rowType(
                        Schema.newBuilder()
                                .column("a", DataTypes.STRING())
                                .column("b", DataTypes.STRING())
                                .build());

        assertThatThrownBy(() -> OutputTypeUtils.adaptToRow("scalar", rowType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot adapt an output of type");
    }
}
