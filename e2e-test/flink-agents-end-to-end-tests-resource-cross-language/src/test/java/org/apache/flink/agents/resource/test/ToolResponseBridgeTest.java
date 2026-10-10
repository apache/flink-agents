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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.tools.ToolMetadata;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests content conversion without JSON-serializing binary application metadata. */
public class ToolResponseBridgeTest {

    @Test
    void preservesBlocksAndBinaryMetadataThroughBothBridgeDirections() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("python/flink_agents"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        PythonInterpreter interpreter = new PythonInterpreter(config);
        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(interpreter, () -> new PythonInterpreter(config))) {
            interpreter.exec("from flink_agents.runtime import python_java_utils");
            interpreter.exec(
                    "from flink_agents.runtime.tests import tool_response_bridge as bridge");
            FunctionTool tool =
                    new FunctionTool(
                            new ToolMetadata("media_response", "Return media", "{}"),
                            new PythonFunction(
                                    "flink_agents.runtime.tests.tool_response_bridge",
                                    "media_response"));
            tool.setPythonResourceAdapter(new PythonResourceAdapterImpl(null, manager, null));
            ToolResponse response = tool.call(new ToolParameters(Map.of()));
            assertThat(response.isSuccess()).as(response.getError()).isTrue();
            assertThat(response.getText()).isEqualTo("beforeafter");
            assertThat(response.getBlocks())
                    .containsExactly(
                            new TextBlock("before"),
                            ImageBlock.fromBytes("image/png", new byte[] {(byte) 0xff, 0}),
                            new TextBlock("after"));
            assertThat((byte[]) response.getMetadata().get("binary"))
                    .containsExactly((byte) 0xff, 0);
            assertThat(response.toResultBlock("call").getMetadata()).isEmpty();
            assertThat(response.getExecutionTimeMs()).isEqualTo(7);
            assertThat(response.getToolName()).isEqualTo("media");

            JavaResourceAdapter javaAdapter =
                    new JavaResourceAdapter(null, getClass().getClassLoader());
            assertThat(
                            interpreter.invoke(
                                    "bridge.check_java_response",
                                    javaAdapter,
                                    getClass().getName()))
                    .isEqualTo(true);
        }
    }

    public static ToolResponse mediaResponse() {
        return ToolResponse.success(
                        List.of(
                                new TextBlock("before"),
                                ImageBlock.fromBytes("image/png", new byte[] {(byte) 0xff, 0}),
                                new TextBlock("after")),
                        7,
                        "media")
                .withMetadata(Map.of("binary", new byte[] {(byte) 0xff, 0}));
    }
}
