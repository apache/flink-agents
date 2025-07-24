/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.agents.runtime.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link UpdatableGaugeImpl}. */
class UpdatableGaugeImplTest {

    /** Test updating and getting integer values. */
    @Test
    void testUpdateAndGetInteger() {
        UpdatableGaugeImpl<Integer> intGauge = new UpdatableGaugeImpl<>();

        // Test with positive value
        intGauge.update(42);
        assertEquals(42, intGauge.getValue(), "Should return the updated integer value");

        // Test with zero
        intGauge.update(0);
        assertEquals(0, intGauge.getValue(), "Should return zero");

        // Test with negative value
        intGauge.update(-10);
        assertEquals(-10, intGauge.getValue(), "Should return the negative integer value");

        // Test with maximum value
        intGauge.update(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, intGauge.getValue(), "Should return Integer.MAX_VALUE");

        // Test with minimum value
        intGauge.update(Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, intGauge.getValue(), "Should return Integer.MIN_VALUE");
    }

    /** Test updating and getting double values. */
    @Test
    void testUpdateAndGetDouble() {
        UpdatableGaugeImpl<Double> doubleGauge = new UpdatableGaugeImpl<>();

        // Test with positive value
        doubleGauge.update(3.14);
        assertEquals(3.14, doubleGauge.getValue(), 0.001, "Should return the updated double value");

        // Test with zero
        doubleGauge.update(0.0);
        assertEquals(0.0, doubleGauge.getValue(), 0.001, "Should return zero");

        // Test with negative value
        doubleGauge.update(-2.5);
        assertEquals(
                -2.5, doubleGauge.getValue(), 0.001, "Should return the negative double value");

        // Test with maximum value
        doubleGauge.update(Double.MAX_VALUE);
        assertEquals(
                Double.MAX_VALUE, doubleGauge.getValue(), 0.001, "Should return Double.MAX_VALUE");

        // Test with minimum value
        doubleGauge.update(Double.MIN_VALUE);
        assertEquals(
                Double.MIN_VALUE, doubleGauge.getValue(), 0.001, "Should return Double.MIN_VALUE");
    }

    /** Test updating and getting float values. */
    @Test
    void testUpdateAndGetFloat() {
        UpdatableGaugeImpl<Float> floatGauge = new UpdatableGaugeImpl<>();

        // Test with positive value
        floatGauge.update(2.5f);
        assertEquals(2.5f, floatGauge.getValue(), 0.001f, "Should return the updated float value");

        // Test with zero
        floatGauge.update(0.0f);
        assertEquals(0.0f, floatGauge.getValue(), 0.001f, "Should return zero");

        // Test with negative value
        floatGauge.update(-1.5f);
        assertEquals(
                -1.5f, floatGauge.getValue(), 0.001f, "Should return the negative float value");

        // Test with maximum value
        floatGauge.update(Float.MAX_VALUE);
        assertEquals(
                Float.MAX_VALUE, floatGauge.getValue(), 0.001f, "Should return Float.MAX_VALUE");

        // Test with minimum value
        floatGauge.update(Float.MIN_VALUE);
        assertEquals(
                Float.MIN_VALUE, floatGauge.getValue(), 0.001f, "Should return Float.MIN_VALUE");
    }

    /** Test updating and getting long values. */
    @Test
    void testUpdateAndGetLong() {
        UpdatableGaugeImpl<Long> longGauge = new UpdatableGaugeImpl<>();

        // Test with positive value
        longGauge.update(10000000000L);
        assertEquals(10000000000L, longGauge.getValue(), "Should return the updated long value");

        // Test with zero
        longGauge.update(0L);
        assertEquals(0L, longGauge.getValue(), "Should return zero");

        // Test with negative value
        longGauge.update(-5000000000L);
        assertEquals(-5000000000L, longGauge.getValue(), "Should return the negative long value");

        // Test with maximum value
        longGauge.update(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, longGauge.getValue(), "Should return Long.MAX_VALUE");

        // Test with minimum value
        longGauge.update(Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, longGauge.getValue(), "Should return Long.MIN_VALUE");
    }

    /** Test initial state. */
    @Test
    void testInitialState() {
        UpdatableGaugeImpl<Integer> intGauge = new UpdatableGaugeImpl<>();
        assertNull(intGauge.getValue(), "Initial value should be null");
    }
}
