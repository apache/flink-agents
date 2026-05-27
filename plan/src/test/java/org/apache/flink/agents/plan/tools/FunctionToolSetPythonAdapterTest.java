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
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionToolSetPythonAdapterTest {

    @Test
    void replacesPlaceholderMetadataForPythonFunction() {
        ToolMetadata placeholder = new ToolMetadata("notify", "", "{}");
        PythonFunction pf = new PythonFunction("pkg.mod", "notify");
        FunctionTool tool = new FunctionTool(placeholder, pf);

        PythonResourceAdapter adapter = Mockito.mock(PythonResourceAdapter.class);
        when(adapter.getPythonToolMetadata("pkg.mod", "notify"))
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
        verify(adapter, times(1)).getPythonToolMetadata(eq("pkg.mod"), eq("notify"));
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
        verify(adapter, never()).getPythonToolMetadata(Mockito.anyString(), Mockito.anyString());
    }

    /** Helper static method to back JavaFunction in the no-op test. */
    public static int stubMethod(int x) {
        return x;
    }
}
