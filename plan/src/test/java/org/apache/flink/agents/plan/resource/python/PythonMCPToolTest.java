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

package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import pemja.core.object.PyObject;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonMCPToolTest {

    @Test
    void preservesExplicitPythonMcpToolFailure() {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pythonTool = mock(PyObject.class);
        when(adapter.invoke("python_java_utils.get_java_tool_metadata_from_tool", pythonTool))
                .thenReturn(
                        Map.of(
                                "name", "lookup",
                                "description", "Lookup a value.",
                                "inputSchema", "{\"type\":\"object\"}"));
        when(adapter.invoke(
                        "python_java_utils.invoke_python_tool_instance",
                        pythonTool,
                        Map.of("query", "flink")))
                .thenReturn(
                        Map.of(
                                "__flink_agents_tool_result__", "response",
                                "success", false,
                                "error", "retry with a narrower query",
                                "execution_time_ms", 7L,
                                "tool_name", "lookup"));
        PythonMCPTool tool = new PythonMCPTool(adapter, pythonTool, "search-server");

        ToolResponse response = tool.call(new ToolParameters(Map.of("query", "flink")));

        assertThat(response.isError()).isTrue();
        assertThat(response.getError()).isEqualTo("retry with a narrower query");
        assertThat(response.getExecutionTimeMs()).isEqualTo(7L);
        assertThat(response.getToolName()).isEqualTo("lookup");
        verify(adapter)
                .invoke(
                        "python_java_utils.invoke_python_tool_instance",
                        pythonTool,
                        Map.of("query", "flink"));
    }
}
