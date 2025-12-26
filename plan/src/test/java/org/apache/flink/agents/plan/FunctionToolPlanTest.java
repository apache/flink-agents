/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FunctionToolPlanTest {

    // Java static tool method
    public static double calc(
            @ToolParam(name = "a") double a,
            @ToolParam(name = "b") double b,
            @ToolParam(name = "operation") String op) {
        switch (op.toLowerCase()) {
            case "add":
                return a + b;
            case "sub":
                return a - b;
            case "mul":
                return a * b;
            case "div":
                if (b == 0) throw new IllegalArgumentException("Division by zero");
                return a / b;
            default:
                throw new IllegalArgumentException("Unknown operation: " + op);
        }
    }

    static class TestAgent extends Agent {
        @Tool private final FunctionTool javaTool;

        @Tool private final FunctionTool pyTool;

        TestAgent() {
            try {
                Method m =
                        FunctionToolPlanTest.class.getMethod(
                                "calc", double.class, double.class, String.class);
                this.javaTool = FunctionTool.fromStaticMethod("java calculator", m);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            // Build a minimal metadata for a non-Java function tool using PythonFunction
            ObjectMapper om = new ObjectMapper();
            ObjectNode schema = om.createObjectNode();
            schema.put("type", "object");
            ToolMetadata md = new ToolMetadata("py_tool", "python tool", schema.asText());
            PythonFunction pyFunc = new PythonFunction("mod", "qual");
            this.pyTool = new FunctionTool(md, pyFunc);
        }
    }

    @Test
    @DisplayName("FunctionTool via AgentPlan: JavaFunction success, PythonFunction error")
    void functionToolAgentPlan() throws Exception {
        AgentPlan plan = new AgentPlan(new TestAgent());

        FunctionTool javaTool = (FunctionTool) plan.getResource("javaTool", ResourceType.TOOL);
        ToolResponse ok =
                javaTool.call(
                        new ToolParameters(
                                new HashMap<>(
                                        Map.of(
                                                "a", 12.0,
                                                "b", 3.0,
                                                "operation", "mul"))));
        assertTrue(ok.isSuccess());
        assertEquals(36.0, (Double) ok.getResult(), 1e-9);

        FunctionTool pyTool = (FunctionTool) plan.getResource("pyTool", ResourceType.TOOL);
        ToolResponse err = pyTool.call(new ToolParameters(new HashMap<>(Map.of("x", 1))));
        assertFalse(err.isSuccess());
    }

    @Test
    @DisplayName("FunctionTool.fromStaticMethod maps @ToolParam by name")
    void javaFunctionMapping() throws Exception {
        Method m =
                FunctionToolPlanTest.class.getMethod(
                        "calc", double.class, double.class, String.class);
        FunctionTool tool = FunctionTool.fromStaticMethod("desc", m);
        ToolResponse ok =
                tool.call(
                        new ToolParameters(
                                new HashMap<>(Map.of("a", 10, "b", 2.5, "operation", "div"))));
        assertTrue(ok.isSuccess());
        assertEquals(4.0, (Double) ok.getResult(), 1e-9);
    }
}
