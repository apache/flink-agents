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
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class FunctionSchemaTest {
    static int calls;

    public static class Options {
        @ToolParam(minLength = 1)
        public String name;
    }

    public static String contractTool(
            @ToolParam(name = "query", minLength = 1) String query,
            @ToolParam(name = "options") Options options,
            @ToolParam(name = "tenant_id", injected = true) String tenant,
            @ToolParam(name = "limit", defaultValue = "10", minimum = "1", maximum = "100")
                    int limit,
            @ToolParam(name = "note", nullable = true, defaultValue = "null") String note) {
        calls++;
        return query
                + ":"
                + options.name
                + ":"
                + limit
                + ":"
                + tenant
                + ":"
                + (note == null ? "" : note);
    }

    public static String invalidDefault(@ToolParam(minimum = "1", defaultValue = "0") int value) {
        throw new AssertionError("must not invoke");
    }

    public static FunctionTool tool() throws Exception {
        return FunctionTool.fromStaticMethod(
                FunctionSchemaTest.class.getMethod(
                        "contractTool",
                        String.class,
                        Options.class,
                        String.class,
                        int.class,
                        String.class));
    }

    public static JsonNode cases() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("e2e-test/function-schema-cases.json"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Cannot locate contract fixtures");
        }
        return new ObjectMapper()
                .readTree(root.resolve("e2e-test/function-schema-cases.json").toFile());
    }

    @Test
    void appliesSharedContractBeforeInvocation() throws Exception {
        FunctionTool tool = tool();
        for (JsonNode test : cases()) {
            calls = 0;
            Map<String, Object> arguments =
                    new ObjectMapper().convertValue(test.get("arguments"), Map.class);
            arguments.put("tenant_id", "tenant");
            ToolResponse result = tool.call(new ToolParameters(arguments));
            if (test.has("error")) {
                assertThat(result.isSuccess()).as(test.get("name").asText()).isFalse();
                assertThat(result.getError())
                        .as(test.get("name").asText())
                        .isEqualTo(test.get("error").asText());
                assertThat(calls).isZero();
            } else {
                assertThat(result.isSuccess()).as(result.getError()).isTrue();
                assertThat(result.getText()).isEqualTo(test.get("result").asText());
                assertThat(calls).isEqualTo(1);
            }
        }
    }

    @Test
    void rejectsInvalidDefaultsDuringCompilation() throws Exception {
        assertThatThrownBy(
                        () ->
                                FunctionTool.fromStaticMethod(
                                        FunctionSchemaTest.class.getMethod(
                                                "invalidDefault", int.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tool default");
    }

    public static int boundedInt(@ToolParam(name = "value", maximum = "999999999999") int value) {
        return value;
    }

    public static int invalidConstraint(@ToolParam(minLength = 1) int value) {
        return value;
    }

    @Test
    void retainsNativeNumericBounds() throws Exception {
        FunctionTool tool =
                FunctionTool.fromStaticMethod(getClass().getMethod("boundedInt", int.class));
        ToolResponse result = tool.call(new ToolParameters(Map.of("value", 2147483648L)));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("INVALID_ARGUMENT /value: maximum");
    }

    @Test
    void rejectsConstraintsForTheWrongType() {
        assertThatThrownBy(
                        () ->
                                FunctionTool.fromStaticMethod(
                                        getClass().getMethod("invalidConstraint", int.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constraint does not match");
    }

    public static int constrainedArgument(
            @ToolParam(name = "value", minimum = "1", defaultValue = "2") int value) {
        return value;
    }

    @Test
    void hiddenParametersKeepTheCompleteArgumentContract() throws Exception {
        java.lang.reflect.Method method = getClass().getMethod("constrainedArgument", int.class);
        FunctionSchema visible = new FunctionSchema(method, java.util.List.of());
        FunctionSchema hidden = new FunctionSchema(method, java.util.List.of("value"));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(
                        mapper.readTree(visible.getMetadata().getInputSchema())
                                .get("properties")
                                .has("value"))
                .isTrue();
        assertThat(
                        mapper.readTree(hidden.getMetadata().getInputSchema())
                                .get("properties")
                                .has("value"))
                .isFalse();
        for (FunctionSchema schema : java.util.List.of(visible, hidden)) {
            assertThat(schema.bind(Map.of())).containsExactly(2);
            assertThat(schema.bind(Map.of("value", 3))).containsExactly(3);
            assertThatThrownBy(() -> schema.bind(Map.of("value", 0)))
                    .hasMessage("INVALID_ARGUMENT /value: minimum");
            assertThatThrownBy(() -> schema.bind(Map.of("value", "3")))
                    .hasMessage("INVALID_ARGUMENT /value: type");
        }
    }
}
