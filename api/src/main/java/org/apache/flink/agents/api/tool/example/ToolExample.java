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

package org.apache.flink.agents.api.tool.example;

import org.apache.flink.agents.api.tool.*;
import org.apache.flink.agents.api.tool.annotation.Tool;
import org.apache.flink.agents.api.tool.annotation.ToolParam;

/**
 * Example demonstrating how to create and use tools This corresponds to the usage patterns shown in
 * the Python implementation.
 */
public class ToolExample {

    /** Example tool for mathematical calculations. */
    @Tool(name = "calculate", description = "Performs basic mathematical operations on two numbers")
    public double calculate(
            @ToolParam(description = "The operation to perform: add, subtract, multiply, divide")
                    String operation,
            @ToolParam(description = "First number") double a,
            @ToolParam(description = "Second number") double b) {
        switch (operation.toLowerCase()) {
            case "add":
                return a + b;
            case "subtract":
                return a - b;
            case "multiply":
                return a * b;
            case "divide":
                if (b == 0) {
                    throw new IllegalArgumentException("Cannot divide by zero");
                }
                return a / b;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    /** Example tool for string manipulation. */
    @Tool(name = "format_text", description = "Formats text with various options")
    public String formatText(
            @ToolParam(description = "The text to format") String text,
            @ToolParam(description = "Convert to uppercase", required = false) boolean uppercase,
            @ToolParam(description = "Prefix to add", required = false) String prefix) {
        String result = text;

        if (uppercase) {
            result = result.toUpperCase();
        }

        if (prefix != null && !prefix.isEmpty()) {
            result = prefix + result;
        }

        return result;
    }

    /** Static tool example. */
    @Tool(name = "get_timestamp", description = "Gets the current timestamp in milliseconds")
    public static long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /** Example of how to use the tool system. */
    public static void main(String[] args) {
        // Create tool registry
        ToolRegistry registry = new ToolRegistry();

        // Register tools from an instance
        ToolExample example = new ToolExample();
        registry.registerToolsFromObject(example);

        // Register static tools from class
        registry.registerToolsFromClass(ToolExample.class);

        // Example 1: Use the calculate tool
        ToolParameters calcParams = new ToolParameters();
        calcParams.addParameter("operation", "add");
        calcParams.addParameter("a", 10.5);
        calcParams.addParameter("b", 5.2);

        ToolResponse calcResult = registry.invokeTool("calculate", calcParams);

        if (calcResult.isSuccess()) {
            System.out.println("Calculate result: " + calcResult.getResult());
            System.out.println("Execution time: " + calcResult.getExecutionTimeMs() + "ms");
        } else {
            System.err.println("Calculate error: " + calcResult.getError());
        }

        // Example 2: Use the format_text tool with default parameters
        ToolParameters formatParams = new ToolParameters();
        formatParams.addParameter("text", "hello world");
        formatParams.addParameter("uppercase", true);

        ToolResponse formatResult = registry.invokeTool("format_text", formatParams);

        if (formatResult.isSuccess()) {
            System.out.println("Format result: " + formatResult.getResult());
        }

        // Example 3: Use static tool
        ToolParameters timestampParams = new ToolParameters();
        ToolResponse timestampResult = registry.invokeTool("get_timestamp", timestampParams);

        if (timestampResult.isSuccess()) {
            System.out.println("Timestamp: " + timestampResult.getResult());
        }

        // Display all registered tools
        System.out.println("\nRegistered tools:");
        for (String toolName : registry.getToolNames()) {
            ToolMetadata metadata = registry.getTool(toolName).getMetadata();
            System.out.println("- " + toolName + ": " + metadata.getDescription());
            System.out.println("  Schema: " + metadata.getInputSchema());
        }
    }
}
