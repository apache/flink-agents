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
package org.apache.flink.agents.api.embedding.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Test for {@link EmbeddingModelUtils}. */
public class EmbeddingModelUtilsTest {

    @Test
    @DisplayName("Test converting List<Double> to float array")
    void testToFloatArrayFromDoubleList() {
        List<Double> doubleList = Arrays.asList(1.0, 2.5, 3.7, 4.2);

        float[] result = EmbeddingModelUtils.toFloatArray(doubleList);

        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals(1.0f, result[0], 0.0001f);
        assertEquals(2.5f, result[1], 0.0001f);
        assertEquals(3.7f, result[2], 0.0001f);
        assertEquals(4.2f, result[3], 0.0001f);
    }

    @Test
    @DisplayName("Test converting List<Float> to float array")
    void testToFloatArrayFromFloatList() {
        List<Float> floatList = Arrays.asList(1.5f, 2.5f, 3.5f);

        float[] result = EmbeddingModelUtils.toFloatArray(floatList);

        assertNotNull(result);
        assertEquals(3, result.length);
        assertArrayEquals(new float[] {1.5f, 2.5f, 3.5f}, result, 0.0001f);
    }

    @Test
    @DisplayName("Test converting mixed Number types to float array")
    void testToFloatArrayFromMixedNumberList() {
        List<Number> mixedList = new ArrayList<>();
        mixedList.add(1); // Integer
        mixedList.add(2.5); // Double
        mixedList.add(3.5f); // Float
        mixedList.add(4L); // Long

        float[] result = EmbeddingModelUtils.toFloatArray(mixedList);

        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals(1.0f, result[0], 0.0001f);
        assertEquals(2.5f, result[1], 0.0001f);
        assertEquals(3.5f, result[2], 0.0001f);
        assertEquals(4.0f, result[3], 0.0001f);
    }

    @Test
    @DisplayName("Test converting empty list to empty float array")
    void testToFloatArrayFromEmptyList() {
        List<Double> emptyList = new ArrayList<>();

        float[] result = EmbeddingModelUtils.toFloatArray(emptyList);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Test converting single element list to float array")
    void testToFloatArrayFromSingleElementList() {
        List<Double> singleList = List.of(42.0);

        float[] result = EmbeddingModelUtils.toFloatArray(singleList);

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(42.0f, result[0], 0.0001f);
    }

    @Test
    @DisplayName("Test exception when list contains non-numeric value")
    void testToFloatArrayThrowsExceptionForNonNumericValue() {
        List<Object> invalidList = new ArrayList<>();
        invalidList.add(1.0);
        invalidList.add("not a number");
        invalidList.add(3.0);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> EmbeddingModelUtils.toFloatArray(invalidList));

        assertNotNull(exception.getMessage());
        assertEquals(
                "Expected numeric value in embedding result, but got: java.lang.String",
                exception.getMessage());
    }
}
