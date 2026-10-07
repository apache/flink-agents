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

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.types.AbstractDataType;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.Row;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Output-type conversion helpers for the typed {@code toTable(Schema)} terminal.
 *
 * <p>An agent emits arbitrary output objects through a {@code DataStream<Object>}. Turning that
 * untyped stream into a {@link org.apache.flink.table.api.Table} with a user-declared {@link
 * Schema} happens in a downstream conversion operator, so the raw stream keeps its unrestricted
 * {@code Object} element type and heterogeneous output stays available through the raw view.
 *
 * <p>This class supplies the two pieces that operator needs:
 *
 * <ul>
 *   <li>{@link #schemaToRowTypeInfo(Schema)} derives the {@link RowTypeInfo} of the physical
 *       columns a {@link Schema} declares. Computed and metadata columns are skipped because the
 *       planner derives them, and only physical columns are read from the agent output.
 *   <li>{@link #adaptToRow(Object, RowTypeInfo)} adapts one emitted value into a {@link Row} that
 *       matches that row type, accepting a {@link Row}, a {@link Map}, a POJO (read by field name),
 *       or a scalar (mapped into a single-column row type).
 * </ul>
 */
public final class OutputTypeUtils {

    private OutputTypeUtils() {}

    /**
     * Derives a {@link RowTypeInfo} from the physical columns of a Table {@link Schema}.
     *
     * <p>Each physical column must carry a resolved {@link DataType} (as produced by {@code
     * DataTypes.STRING()} and friends); computed and metadata columns are ignored because the
     * planner derives them rather than reading them from the agent output.
     *
     * @param schema the output schema declared by the user.
     * @return a row type whose field names and types follow the schema's physical columns, in
     *     order.
     * @throws IllegalArgumentException if the schema has no physical columns, or a physical
     *     column's type is not a resolved {@link DataType}.
     */
    public static RowTypeInfo schemaToRowTypeInfo(Schema schema) {
        List<String> names = new ArrayList<>();
        List<TypeInformation<?>> types = new ArrayList<>();
        for (Schema.UnresolvedColumn column : schema.getColumns()) {
            if (!(column instanceof Schema.UnresolvedPhysicalColumn)) {
                continue;
            }
            AbstractDataType<?> abstractType =
                    ((Schema.UnresolvedPhysicalColumn) column).getDataType();
            if (!(abstractType instanceof DataType)) {
                throw new IllegalArgumentException(
                        "Cannot derive the output row type from column '"
                                + column.getName()
                                + "': its type "
                                + abstractType
                                + " is not a resolved DataType. Build the schema with concrete"
                                + " types such as DataTypes.STRING().");
            }
            names.add(column.getName());
            types.add(TypeConversions.fromDataTypeToLegacyInfo((DataType) abstractType));
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot derive an output row type: the schema declares no physical columns.");
        }
        return new RowTypeInfo(types.toArray(new TypeInformation[0]), names.toArray(new String[0]));
    }

    /**
     * Adapts a single agent output value into a {@link Row} matching {@code rowType}.
     *
     * <p>This is the payload of the {@code toTable(Schema)} conversion operator. The accepted
     * shapes are:
     *
     * <ul>
     *   <li>a {@link Row}: projected by name when named, otherwise passed through positionally
     *       after an arity check;
     *   <li>a {@link Map}: read by the row type's field names;
     *   <li>an object exposing every column name (a POJO): read by name via getters or fields;
     *   <li>otherwise a scalar value with a single-column row type: wrapped into a one-column row.
     * </ul>
     *
     * @param value the emitted output object.
     * @param rowType the target row type derived from the output schema.
     * @return a positional {@link Row} whose fields follow {@code rowType}'s order.
     * @throws IllegalArgumentException if the value cannot supply the declared fields.
     */
    public static Row adaptToRow(Object value, RowTypeInfo rowType) {
        String[] names = rowType.getFieldNames();
        int arity = names.length;
        if (value instanceof Row) {
            return fromRow((Row) value, names, arity);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object[] fields = new Object[arity];
            for (int i = 0; i < arity; i++) {
                fields[i] = map.get(names[i]);
            }
            return Row.of(fields);
        }
        if (canReadAll(value, names)) {
            Object[] fields = new Object[arity];
            for (int i = 0; i < arity; i++) {
                fields[i] = readProperty(value, names[i], rowType);
            }
            return Row.of(fields);
        }
        if (arity == 1) {
            // A scalar output mapped into a single-column table.
            return Row.of(value);
        }
        throw new IllegalArgumentException(
                "Cannot adapt an output of type "
                        + value.getClass().getName()
                        + " to the declared schema "
                        + rowType
                        + ": it is neither a Row/Map nor an object exposing the columns "
                        + Arrays.toString(names)
                        + ".");
    }

    private static Row fromRow(Row value, String[] names, int arity) {
        Set<String> rowNames = tryFieldNames(value);
        if (rowNames != null && rowNames.containsAll(Arrays.asList(names))) {
            Object[] fields = new Object[arity];
            for (int i = 0; i < arity; i++) {
                fields[i] = value.getField(names[i]);
            }
            return Row.of(fields);
        }
        if (value.getArity() != arity) {
            throw new IllegalArgumentException(
                    "Agent emitted a Row with "
                            + value.getArity()
                            + " fields, but the output schema declares "
                            + arity
                            + " columns "
                            + Arrays.toString(names)
                            + ".");
        }
        return value;
    }

    private static Set<String> tryFieldNames(Row row) {
        try {
            return row.getFieldNames(false);
        } catch (RuntimeException e) {
            // A positional row has no field names; fall back to positional adaptation.
            return null;
        }
    }

    private static Object readProperty(Object bean, String name, RowTypeInfo rowType) {
        Class<?> type = bean.getClass();
        Method getter = findGetter(type, name);
        if (getter != null) {
            try {
                return getter.invoke(bean);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalArgumentException(
                        "Failed to read output field '" + name + "' from " + type.getName() + ".",
                        e);
            }
        }
        Field field = findField(type, name);
        if (field == null) {
            throw new IllegalArgumentException(
                    "Cannot read output field '"
                            + name
                            + "' from "
                            + type.getName()
                            + " to match the declared schema "
                            + rowType
                            + ". Provide a matching getter or field.");
        }
        try {
            field.setAccessible(true);
            return field.get(bean);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to read output field '" + name + "' from " + type.getName() + ".", e);
        }
    }

    private static boolean canReadAll(Object bean, String[] names) {
        Class<?> type = bean.getClass();
        for (String name : names) {
            if (findGetter(type, name) == null && findField(type, name) == null) {
                return false;
            }
        }
        return true;
    }

    private static Method findGetter(Class<?> type, String name) {
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String methodName : new String[] {"get" + suffix, "is" + suffix}) {
            try {
                Method method = type.getMethod(methodName);
                if (method.getParameterCount() == 0) {
                    return method;
                }
            } catch (NoSuchMethodException e) {
                // try the next candidate
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }
}
