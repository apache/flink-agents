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

package org.apache.flink.agents.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a method as an MCP server resource that should be managed by the agent plan.
 *
 * <p>Methods annotated with @MCPServer will be scanned during agent plan creation. The agent plan
 * will automatically:
 *
 * <ul>
 *   <li>Discover all tools exposed by the MCP server via {@code listTools()}
 *   <li>Discover all prompts exposed by the MCP server via {@code listPrompts()}
 *   <li>Register each tool and prompt as individual resources
 *   <li>Close the MCP server connection after discovery
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *     @MCPServer
 *     public static MCPServer myMcpServer() {
 *         return MCPServer.builder("http://localhost:8000/mcp")
 *             .timeout(Duration.ofSeconds(30))
 *             .build();
 *     }
 *
 *     @ChatModelSetup
 *     public static ChatModel chatModel() {
 *         return new ChatModel.Builder()
 *             .prompt("greeting")  // MCP prompt from server
 *             .tools(List.of("calculator"))  // MCP tool from server
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * <p>This is the Java equivalent of Python's {@code @mcp_server} decorator.
 *
 * @see org.apache.flink.agents.integrations.mcp.MCPServer
 * @see org.apache.flink.agents.integrations.mcp.MCPTool
 * @see org.apache.flink.agents.integrations.mcp.MCPPrompt
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MCPServer {}
