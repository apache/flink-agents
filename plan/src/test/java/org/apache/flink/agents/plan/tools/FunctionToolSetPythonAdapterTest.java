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

import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionToolSetPythonAdapterTest {

    private static final ToolMetadata PYTHON_TOOL_METADATA =
            new ToolMetadata("notify", "Send a notification.", "{\"properties\":{}}");

    @Test
    void replacesPlaceholderMetadataForPythonFunction() {
        ToolMetadata placeholder = new ToolMetadata("notify", "", "{}");
        PythonFunction pf = new PythonFunction("pkg.mod", "notify");
        FunctionTool tool = new FunctionTool(placeholder, pf);

        PythonResourceAdapter adapter = Mockito.mock(PythonResourceAdapter.class);
        when(adapter.getPythonToolMetadata(eq("pkg.mod"), eq("notify"), anyList()))
                .thenReturn(
                        Map.of(
                                "name", "notify",
                                "description", "Send a notification.",
                                "inputSchema",
                                        "{\"properties\":{\"id\":{\"type\":\"string\","
                                                + "\"description\":\"recipient id\"}}}"));

        tool.setPythonResourceAdapter(adapter);

        assertThat(tool.getMetadata().getName()).isEqualTo("notify");
        assertThat(tool.getMetadata().getDescription()).isEqualTo("Send a notification.");
        assertThat(tool.getMetadata().getInputSchema()).contains("recipient id");
        verify(adapter, times(1)).getPythonToolMetadata(eq("pkg.mod"), eq("notify"), anyList());
    }

    @Test
    void mergesPythonCallableInjectedArgsFromAdapter() {
        ToolMetadata placeholder = new ToolMetadata("notify", "", "{}");
        PythonFunction pf = new PythonFunction("pkg.mod", "notify");
        FunctionTool tool =
                new FunctionTool(
                        placeholder,
                        pf,
                        Map.of(
                                "request_id",
                                ToolParameterInjection.fromSensoryMemory("request.id")));

        PythonResourceAdapter adapter = Mockito.mock(PythonResourceAdapter.class);
        when(adapter.getPythonToolMetadata(eq("pkg.mod"), eq("notify"), anyList()))
                .thenReturn(
                        Map.of(
                                "name", "notify",
                                "description", "Send a notification.",
                                "inputSchema", "{\"properties\":{\"id\":{\"type\":\"string\"}}}",
                                "injectedArgs",
                                        "{\"tenant_id\":{\"source\":\"config\","
                                                + "\"key\":\"tenant.id\"}}"));

        tool.setPythonResourceAdapter(adapter);

        assertThat(tool.getInjectedArgs())
                .containsEntry("tenant_id", ToolParameterInjection.fromConfig("tenant.id"))
                .containsEntry(
                        "request_id", ToolParameterInjection.fromSensoryMemory("request.id"));
    }

    @Test
    void noOpForJavaFunction() throws Exception {
        ToolMetadata original = new ToolMetadata("add", "Adds.", "{\"properties\":{}}");
        JavaFunction jf =
                new JavaFunction(
                        FunctionToolSetPythonAdapterTest.class,
                        "stubMethod",
                        new Class<?>[] {int.class});
        FunctionTool tool = new FunctionTool(original, jf);

        PythonResourceAdapter adapter = Mockito.mock(PythonResourceAdapter.class);
        tool.setPythonResourceAdapter(adapter);

        // Metadata untouched
        assertThat(tool.getMetadata()).isSameAs(original);
        verify(adapter, never())
                .getPythonToolMetadata(Mockito.anyString(), Mockito.anyString(), anyList());
    }

    @Test
    void preservesExplicitPythonToolFailure() {
        PythonFunction function = new PythonFunction("pkg.mod", "notify");
        FunctionTool tool = new FunctionTool(PYTHON_TOOL_METADATA, function);
        PythonResourceAdapter adapter = pythonAdapter();
        when(adapter.invokePythonTool(eq("pkg.mod"), eq("notify"), eq(Map.of("id", "1"))))
                .thenReturn(
                        Map.of(
                                "__flink_agents_tool_result__", "response",
                                "success", false,
                                "error", "recipient not found",
                                "execution_time_ms", 7L,
                                "tool_name", "notify"));
        tool.setPythonResourceAdapter(adapter);

        ToolResponse response = tool.call(new ToolParameters(Map.of("id", "1")));

        assertThat(response.isError()).isTrue();
        assertThat(response.getError()).isEqualTo("recipient not found");
        assertThat(response.getExecutionTimeMs()).isEqualTo(7L);
        assertThat(response.getToolName()).isEqualTo("notify");
    }

    @Test
    void preservesExplicitPythonToolSuccess() {
        PythonFunction function = new PythonFunction("pkg.mod", "notify");
        FunctionTool tool = new FunctionTool(PYTHON_TOOL_METADATA, function);
        PythonResourceAdapter adapter = pythonAdapter();
        when(adapter.invokePythonTool(eq("pkg.mod"), eq("notify"), eq(Map.of("id", "1"))))
                .thenReturn(
                        Map.of(
                                "__flink_agents_tool_result__", "response",
                                "result", "sent",
                                "success", true,
                                "execution_time_ms", 5L,
                                "tool_name", "notify"));
        tool.setPythonResourceAdapter(adapter);

        ToolResponse response = tool.call(new ToolParameters(Map.of("id", "1")));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResult()).isEqualTo("sent");
        assertThat(response.getExecutionTimeMs()).isEqualTo(5L);
        assertThat(response.getToolName()).isEqualTo("notify");
    }

    @Test
    void unwrapsRawPythonToolResultEnvelope() {
        PythonFunction function = new PythonFunction("pkg.mod", "notify");
        FunctionTool tool = new FunctionTool(PYTHON_TOOL_METADATA, function);
        PythonResourceAdapter adapter = pythonAdapter();
        Map<String, Object> rawResult =
                Map.of("__flink_agents_tool_result__", "response", "value", "raw");
        when(adapter.invokePythonTool(eq("pkg.mod"), eq("notify"), eq(Map.of("id", "1"))))
                .thenReturn(Map.of("__flink_agents_tool_result__", "raw", "result", rawResult));
        tool.setPythonResourceAdapter(adapter);

        ToolResponse response = tool.call(new ToolParameters(Map.of("id", "1")));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResult()).isEqualTo(rawResult);
    }

    private static PythonResourceAdapter pythonAdapter() {
        PythonResourceAdapter adapter = Mockito.mock(PythonResourceAdapter.class);
        when(adapter.getPythonToolMetadata(eq("pkg.mod"), eq("notify"), anyList()))
                .thenReturn(
                        Map.of(
                                "name", "notify",
                                "description", "Send a notification.",
                                "inputSchema", "{\"properties\":{}}"));
        return adapter;
    }

    /** Helper static method to back JavaFunction in the no-op test. */
    public static int stubMethod(int x) {
        return x;
    }
}
