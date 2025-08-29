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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.configuration.ConfigOption;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgentConfigurationTest {

    @Test
    void testGetInt() {
        Map<String, Object> data = new HashMap<>();
        data.put("int_key", 42);
        data.put("str_key", "123");
        data.put("invalid_key", "not_an_int");
        AgentConfiguration config = new AgentConfiguration(data);

        assertEquals(Integer.valueOf(42), config.getInt("int_key", null));
        assertEquals(Integer.valueOf(123), config.getInt("str_key", null));
        assertNull(config.getInt("missing_key", null));
        assertEquals(Integer.valueOf(999), config.getInt("missing_key", 999));

        assertThrows(
                NumberFormatException.class,
                () -> {
                    config.getInt("invalid_key", null);
                });
    }

    @Test
    void testGetLong() {
        Map<String, Object> data = new HashMap<>();
        data.put("long_key", 123456789012L);
        data.put("str_long_key", "9876543210");
        data.put("invalid_long_key", "not_a_long");

        AgentConfiguration config = new AgentConfiguration(data);

        assertEquals(Long.valueOf(123456789012L), config.getLong("long_key", null));

        assertEquals(Long.valueOf(9876543210L), config.getLong("str_long_key", null));

        assertNull(config.getLong("missing_key", null));

        assertEquals(Long.valueOf(999999999999L), config.getLong("missing_key", 999999999999L));

        assertThrows(
                NumberFormatException.class,
                () -> {
                    config.getLong("invalid_long_key", null);
                });
    }

    @Test
    void testGetFloat() {
        Map<String, Object> data = new HashMap<>();
        data.put("float_key", 3.14f);
        data.put("int_key", 42);
        data.put("str_key", "2.5");
        data.put("invalid_key", "not_a_float");
        AgentConfiguration config = new AgentConfiguration(data);

        assertEquals(Float.valueOf(3.14f), config.getFloat("float_key", null));
        assertEquals(Float.valueOf(42.0f), config.getFloat("int_key", null));
        assertEquals(Float.valueOf(2.5f), config.getFloat("str_key", null));
        assertNull(config.getFloat("missing_key", null));
        assertEquals(Float.valueOf(1.23f), config.getFloat("missing_key", 1.23f));

        assertThrows(
                NumberFormatException.class,
                () -> {
                    config.getFloat("invalid_key", null);
                });
    }

    @Test
    void testGetDouble() {
        Map<String, Object> data = new HashMap<>();
        data.put("double_key", 3.14);
        data.put("int_key", 42);
        data.put("str_key", "2.5");
        data.put("invalid_key", "not_a_double");

        AgentConfiguration config = new AgentConfiguration(data);

        assertEquals(Double.valueOf(3.14), config.getDouble("double_key", null));

        assertEquals(Double.valueOf(42.0), config.getDouble("int_key", null));

        assertEquals(Double.valueOf(2.5), config.getDouble("str_key", null));

        assertNull(config.getDouble("missing_key", null));

        assertEquals(Double.valueOf(1.23), config.getDouble("missing_key", 1.23));

        assertThrows(
                NumberFormatException.class,
                () -> {
                    config.getDouble("invalid_key", null);
                });
    }

    @Test
    void testGetBool() {
        Map<String, Object> data = new HashMap<>();
        data.put("true_key", true);
        data.put("false_key", false);
        data.put("str_key", "true");
        AgentConfiguration config = new AgentConfiguration(data);

        assertTrue(config.getBool("true_key", false));
        assertFalse(config.getBool("false_key", true));
        assertNull(config.getBool("missing_key", null));
        assertTrue(config.getBool("missing_key", true));

        // Note: Boolean.valueOf("true") is true in Java
        assertTrue(config.getBool("str_key", null));
    }

    @Test
    void testGetStr() {
        Map<String, Object> data = new HashMap<>();
        data.put("str_key", "hello");
        data.put("int_key", 42);
        data.put("float_key", 3.14);
        AgentConfiguration config = new AgentConfiguration(data);

        assertEquals("hello", config.getStr("str_key", null));
        assertEquals("42", config.getStr("int_key", null));
        assertEquals("3.14", config.getStr("float_key", null));
        assertNull(config.getStr("missing_key", null));
        assertEquals("default", config.getStr("missing_key", "default"));
    }

    @Test
    void testGetWithConfigOption() {
        Map<String, Object> data = new HashMap<>();
        data.put("config.str", "config.value");
        data.put("config.int", 6789);
        data.put("config.float", "45.5");
        data.put("config.boolean", true);

        AgentConfiguration config = new AgentConfiguration(data);

        ConfigOption<String> strOption =
                new ConfigOption<>("config.str", String.class, "default_str");
        ConfigOption<Integer> intOption = new ConfigOption<>("config.int", Integer.class, 123);
        ConfigOption<Long> longOption = new ConfigOption<>("config.int", Long.class, 123L);
        ConfigOption<Float> floatOption = new ConfigOption<>("config.float", Float.class, 0.0f);
        ConfigOption<Double> doubleOption = new ConfigOption<>("config.float", Double.class, 0.0);
        ConfigOption<Boolean> boolOption =
                new ConfigOption<>("config.boolean", Boolean.class, false);

        assertEquals("config.value", config.get(strOption));
        assertEquals(Integer.valueOf(6789), config.get(intOption));
        assertEquals(Long.valueOf(6789L), config.get(longOption));
        assertEquals(Float.valueOf(45.5f), config.get(floatOption));
        assertEquals(Double.valueOf(45.5), config.get(doubleOption));
        assertEquals(Boolean.TRUE, config.get(boolOption));

        ConfigOption<Integer> missingOption = new ConfigOption<>("missing.key1", Integer.class, 22);
        assertEquals(Integer.valueOf(22), config.get(missingOption));

        ConfigOption<Integer> missingKey = new ConfigOption<>("missing.key2", Integer.class, null);
        assertNull(config.get(missingKey));
    }

    @Test
    void testGetWithDefaultValue() {
        ConfigOption<String> defaultStr =
                new ConfigOption<>("default.str", String.class, "default_value");
        ConfigOption<Integer> defaultInt = new ConfigOption<>("default.int", Integer.class, 100);
        ConfigOption<Double> defaultDouble =
                new ConfigOption<>("default.double", Double.class, 2.5);

        AgentConfiguration config = new AgentConfiguration();

        assertEquals("default_value", config.get(defaultStr));
        assertEquals(Integer.valueOf(100), config.get(defaultInt));
        assertEquals(Double.valueOf(2.5), config.get(defaultDouble));
    }

    @Test
    void testGetWithNullAndDefault() {
        ConfigOption<String> nullableStr =
                new ConfigOption<>("nullable.str", String.class, "default");

        AgentConfiguration config = new AgentConfiguration();
        config.setStr("nullable.str", null);

        assertEquals("default", config.get(nullableStr));
    }
}
