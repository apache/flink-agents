package org.apache.flink.agents.plan.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

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

        public void methodWithCustomObject(
                @ToolParam(name = "objectParam", description = "A custom object parameter")
                        Object customObject) {}
    }

    private final ObjectMapper mapper = new ObjectMapper();

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
