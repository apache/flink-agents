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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.plan.JavaFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

public class FunctionToolTest {
    @Tool(description = "Performs basic arithmetic operations")
    public static double calculate(
            @ToolParam(name = "a") Double a,
            @ToolParam(name = "b") Double b,
            @ToolParam(name = "operation") String operation) {
        switch (operation.toLowerCase()) {
            case "add":
                return a + b;
            case "subtract":
                return a - b;
            case "multiply":
                return a * b;
            case "divide":
                if (b == 0) throw new IllegalArgumentException("Division by zero");
                return a / b;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    @Test
    public void testToolMetadataSerializable() throws Exception {
        Method method =
                FunctionToolTest.class.getMethod(
                        "calculate", Double.class, Double.class, String.class);
        ToolMetadata origin = ToolMetadataFactory.fromStaticMethod(method);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(origin);
        ToolMetadata deserialize = mapper.readValue(json, ToolMetadata.class);
        Assertions.assertEquals(origin, deserialize);
    }

    @Test
    public void testFunctionToolSerializable() throws Exception {
        Method method =
                FunctionToolTest.class.getMethod(
                        "calculate", Double.class, Double.class, String.class);
        ToolMetadata metadata = ToolMetadataFactory.fromStaticMethod(method);
        JavaFunction javaFunction =
                new JavaFunction(
                        method.getDeclaringClass(),
                        method.getName(),
                        new Class[] {Double.class, Double.class, String.class});
        FunctionTool tool = new FunctionTool(metadata, javaFunction);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(tool);
        FunctionTool deserialize = mapper.readValue(json, FunctionTool.class);
        Assertions.assertEquals(tool.getMetadata(), deserialize.getMetadata());
        Assertions.assertEquals(tool.getFunction(), deserialize.getFunction());
    }
}
