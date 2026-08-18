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

package org.apache.flink.agents.plan.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaUtilsTest {

    private static class TestClass {
        public void methodWithBasicTypes(
                @ToolParam(name = "stringParam", description = "A string parameter")
                        String strParam,
                @ToolParam(
                                name = "intParam",
                                description = "An integer parameter",
                                required = false)
                        int intParam,
                @ToolParam(name = "boolParam", description = "A boolean parameter")
                        boolean boolParam) {}

        public void methodWithoutAnnotations(String param1, int param2) {}

        public void methodWithWideNumericTypes(
                @ToolParam(name = "longParam", description = "A long parameter") long longParam,
                @ToolParam(name = "boxedLongParam", description = "A boxed long") Long boxedLong,
                @ToolParam(name = "floatParam", description = "A float parameter") float floatParam,
                @ToolParam(name = "boxedFloatParam", description = "A boxed float")
                        Float boxedFloat,
                @ToolParam(name = "shortParam", description = "A short parameter") short shortParam,
                @ToolParam(name = "byteParam", description = "A byte parameter") byte byteParam) {}

        public void methodWithCustomObject(
                @ToolParam(name = "objectParam", description = "A custom object parameter")
                        Object customObject) {}

        public void methodWithExternallyInjectedRequiredParam(
                @ToolParam(name = "order_id", description = "The order id") String orderId,
                @ToolParam(name = "tenant_id", description = "The tenant id") String tenantId) {}
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testGenerateSchemaWithWideNumericTypes() throws Exception {
        Method method =
                TestClass.class.getMethod(
                        "methodWithWideNumericTypes",
                        long.class,
                        Long.class,
                        float.class,
                        Float.class,
                        short.class,
                        byte.class);
        String schema = SchemaUtils.generateSchema(method);
        final JsonNode jsonNode = mapper.readTree(schema);
        JsonNode properties = jsonNode.get("properties");

        // Integral types map to "integer" (#1015): previously these all fell back to "object",
        // which models either follow (producing object-shaped arguments) or treat as an
        // uncallable tool.
        assertEquals("integer", properties.get("longParam").get("type").asText());
        assertEquals("integer", properties.get("boxedLongParam").get("type").asText());
        assertEquals("integer", properties.get("shortParam").get("type").asText());
        assertEquals("integer", properties.get("byteParam").get("type").asText());

        // Floating-point types map to "number".
        assertEquals("number", properties.get("floatParam").get("type").asText());
        assertEquals("number", properties.get("boxedFloatParam").get("type").asText());
    }

    @Test
    void testGenerateSchemaWithBasicTypes() throws Exception {
        Method method =
                TestClass.class.getMethod(
                        "methodWithBasicTypes", String.class, int.class, boolean.class);
        String schema = SchemaUtils.generateSchema(method);
        final JsonNode jsonNode = mapper.readTree(schema);

        // Validate basic schema structure
        assertEquals("object", jsonNode.get("type").asText());
        assertTrue(jsonNode.has("properties"));
        assertTrue(jsonNode.has("required"));

        // Validate properties
        JsonNode properties = jsonNode.get("properties");

        // Validate String parameter
        assertTrue(properties.has("stringParam"));
        assertEquals("string", properties.get("stringParam").get("type").asText());
        assertEquals(
                "A string parameter", properties.get("stringParam").get("description").asText());

        // Validate Integer parameter
        assertTrue(properties.has("intParam"));
        assertEquals("integer", properties.get("intParam").get("type").asText());
        assertEquals(
                "An integer parameter", properties.get("intParam").get("description").asText());

        // Validate Boolean parameter
        assertTrue(properties.has("boolParam"));
        assertEquals("boolean", properties.get("boolParam").get("type").asText());
        assertEquals(
                "A boolean parameter", properties.get("boolParam").get("description").asText());

        // Validate required fields
        JsonNode required = jsonNode.get("required");
        assertTrue(required.isArray());
        assertEquals(2, required.size());
        // stringParam and boolParam should be required (default is true)
        assertTrue(required.toString().contains("stringParam"));
        assertTrue(required.toString().contains("boolParam"));
        // intParam should not be required (explicitly set to false)
        assertFalse(required.toString().contains("intParam"));
    }

    @Test
    void testGenerateSchemaHidesExternallyInjectedParameter() throws Exception {
        Method method =
                TestClass.class.getMethod(
                        "methodWithBasicTypes", String.class, int.class, boolean.class);
        String schema = SchemaUtils.generateSchema(method, Set.of("intParam"));
        JsonNode jsonNode = mapper.readTree(schema);

        JsonNode properties = jsonNode.get("properties");
        assertTrue(properties.has("stringParam"));
        assertFalse(properties.has("intParam"));
        assertTrue(properties.has("boolParam"));
        assertFalse(jsonNode.get("required").toString().contains("intParam"));
    }

    @Test
    void testGenerateSchemaHidesExternallyInjectedRequiredParameter() throws Exception {
        Method method =
                TestClass.class.getMethod(
                        "methodWithExternallyInjectedRequiredParam", String.class, String.class);
        String schema = SchemaUtils.generateSchema(method, Set.of("tenant_id"));
        JsonNode jsonNode = mapper.readTree(schema);

        JsonNode properties = jsonNode.get("properties");
        assertTrue(properties.has("order_id"));
        assertFalse(properties.has("tenant_id"));
        assertFalse(jsonNode.get("required").toString().contains("tenant_id"));
        assertFalse(schema.contains("tenant_id"));
    }

    @Test
    void testGenerateSchemaWithCustomObject() throws Exception {
        Method method = TestClass.class.getMethod("methodWithCustomObject", Object.class);
        String schema = SchemaUtils.generateSchema(method);
        JsonNode jsonNode = mapper.readTree(schema);

        // Validate custom object type
        JsonNode properties = jsonNode.get("properties");
        assertTrue(properties.has("objectParam"));
        assertEquals("object", properties.get("objectParam").get("type").asText());
        assertEquals(
                "A custom object parameter",
                properties.get("objectParam").get("description").asText());

        // Validate required field (default is true)
        JsonNode required = jsonNode.get("required");
        assertTrue(required.isArray());
        assertEquals(1, required.size());
        assertTrue(required.toString().contains("objectParam"));
    }
}
