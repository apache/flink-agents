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

package org.apache.flink.agents.resource.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.runtime.python.utils.JavaResourceAdapter;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Exercises both actual Pemja directions, including native nested-object binding. */
public class FunctionSchemaBridgeTest {
    private static int calls;

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

    @Test
    void validatesBothBridgeDirectionsAgainstTheSameFixtures() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("e2e-test/function-schema-cases.json"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Cannot locate fixtures");
        }
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        PythonInterpreter interpreter = new PythonInterpreter(config);
        interpreter.exec("from flink_agents.runtime import python_java_utils");
        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(interpreter, () -> new PythonInterpreter(config))) {
            interpreter.exec(
                    "from flink_agents.runtime.tests import function_schema_bridge as contract");
            JavaResourceAdapter javaAdapter =
                    new JavaResourceAdapter(null, getClass().getClassLoader());
            interpreter.set("schema_java_adapter", javaAdapter);
            PythonResourceAdapterImpl pythonAdapter =
                    new PythonResourceAdapterImpl(null, manager, null);
            FunctionTool pythonTool =
                    new FunctionTool(
                            new org.apache.flink.agents.api.tools.ToolMetadata(
                                    "contract_tool", "", "{}"),
                            new PythonFunction(
                                    "flink_agents.runtime.tests.function_schema_bridge",
                                    "contract_tool"),
                            Map.of("tenant_id", ToolParameterInjection.fromConfig("tenant")));
            pythonTool.setPythonResourceAdapter(pythonAdapter);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode cases =
                    mapper.readTree(root.resolve("e2e-test/function-schema-cases.json").toFile());
            for (JsonNode test : cases) {
                calls = 0;
                interpreter.invoke("contract.reset_calls");
                Map<String, Object> arguments =
                        mapper.convertValue(test.get("arguments"), Map.class);
                arguments.put("tenant_id", "tenant");
                ToolResponse pythonResult = pythonTool.call(new ToolParameters(arguments));
                interpreter.set("schema_payload", test.get("arguments").toString());
                interpreter.set("schema_class", getClass().getName());
                interpreter.exec(
                        "schema_result = contract.call_java(schema_java_adapter, schema_class, schema_payload)");
                JsonNode javaResult = mapper.readTree((String) interpreter.get("schema_result"));
                if (test.has("error")) {
                    assertThat(pythonResult.isSuccess()).as(test.get("name").asText()).isFalse();
                    assertThat(pythonResult.getError()).contains(test.get("error").asText());
                    assertThat(javaResult.get("success").asBoolean()).isFalse();
                    assertThat(javaResult.get("error").asText())
                            .contains(test.get("error").asText());
                    assertThat(calls).isZero();
                    assertThat(((Number) interpreter.invoke("contract.get_calls")).intValue())
                            .isZero();
                } else {
                    assertThat(pythonResult.isSuccess()).as(pythonResult.getError()).isTrue();
                    assertThat(pythonResult.getText()).isEqualTo(test.get("result").asText());
                    assertThat(javaResult.get("success").asBoolean())
                            .as(javaResult.toString())
                            .isTrue();
                    assertThat(javaResult.get("result").asText())
                            .isEqualTo(test.get("result").asText());
                    assertThat(calls).isEqualTo(1);
                    assertThat(((Number) interpreter.invoke("contract.get_calls")).intValue())
                            .isEqualTo(1);
                }
            }
        }
    }
}
