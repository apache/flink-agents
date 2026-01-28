---
title: MCP
weight: 9
type: docs
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

## Overview

MCP (Model Context Protocol) is a standardized protocol for integrating AI applications with external data sources and tools. Flink Agents provides the support for using prompts and tools from MCP server.

## Declare MCP Server in Agent

Developer can declare a mcp server by decorator/annotation when creating an Agent.

{{< tabs "Use MCP Tools in Agent" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):

    @mcp_server
    @staticmethod
    def my_mcp_server() -> ResourceDescriptor:
        """Define MCP server connection."""
        return ResourceDescriptor(clazz=ResourceName.MCP_SERVER, 
                                  endpoint="http://127.0.0.1:8000/mcp")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {

    @MCPServer
    public static ResourceDescriptor myMcp() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", MCP_ENDPOINT)
                    .addInitialArgument("timeout", 30)
                    .build();
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use decorator/annotation to define MCP server connection
  - In Python, use `@mcp_server`.
  - In Java, use `@MCPServer`.
- Use the builder pattern in Java to configure the MCP server with endpoint, timeout, headers, and authentication

### Authentication

MCP servers can be configured with authentication:

{{< tabs "MCP Server Authentication" >}}

{{< tab "Python" >}}
```python
@mcp_server
@staticmethod
def authenticated_mcp_server() -> ResourceDescriptor:
    """Connect to MCP server with authentication."""
    return ResourceDescriptor(clazz=ResourceName.MCP_SERVER, 
                              endpoint="http://api.example.com/mcp",
                              headers={"Authorization": "Bearer your-token"})
    # Or using Basic Authentication
    # credentials = base64.b64encode(b"username:password").decode("ascii")
    # headers={"Authorization": f"Basic {credentials}"}

    # Or using API Key Authentication
    # headers={"X-API-Key": "your-api-key"}
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@MCPServer
public static ResourceDescriptor authenticatedMcpServer() {
    // Using Bearer Token Authentication
    return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", "http://api.example.com/mcp")
                    .addInitialArgument("timeout", 30)
                    .addInitialArgument("auth", new BearerTokenAuth("your-oauth-token"))
                    .build();

    // Or using Basic Authentication
    // .addInitialArgument("auth", new BasicAuth("username", "password"))

    // Or using API Key Authentication
    // .addInitialArgument("auth", new ApiKeyAuth("X-API-Key", "your-api-key"))
}
```
{{< /tab >}}

{{< /tabs >}}

**Authentication options:**
- `BearerTokenAuth` - For OAuth 2.0 and JWT tokens
- `BasicAuth` - For username/password authentication
- `ApiKeyAuth` - For API key authentication via custom headers

## Use MCP prompts and tools in Agent


MCP prompts and tools are managed by external MCP servers and automatically discovered when you define an MCP server connection in your agent.

{{< tabs "Use MCP Prompts and Tools in Agent" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):
  
    @mcp_server
    @staticmethod
    def review_mcp_server() -> ResourceDescriptor:
        """Connect to MCP server."""
        return ResourceDescriptor(clazz=ResourceName.MCP_SERVER, 
                                  endpoint="http://127.0.0.1:8000/mcp")

    @chat_model_setup
    @staticmethod
    def review_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            # Reference MCP prompt by name like local prompt
            prompt="review_analysis_prompt",
            # Reference MCP tool by name like function tool
            tools=["notify_shipping_manager"],
        )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {

    @MCPServer
    public static ResourceDescriptor myMcp() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", "http://127.0.0.1:8000/mcp")
                    .addInitialArgument("timeout", 30)
                    .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                // Reference MCP prompt by name like local prompt
                .addInitialArgument("prompt", "review_analysis_prompt") 
                // Reference MCP tool by name like function tool
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager"))
                .build();
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- All tools and prompts from the MCP server are automatically registered.
- Reference MCP prompts and tools by their names, like reference [local prompt]({{< ref "docs/development/prompts#using-prompts-in-agents" >}}) and [function tool]({{< ref "docs/development/tool_use#define-tool-as-static-method-in-agent-class" >}}) .

## Appendix

### MCP SDK

Flink Agents offers two implementations of MCP support, based on MCP SDKs in different languages (Python and Java). Typically, users do not need to be aware of this, as the framework automatically determines the appropriate implementation based on the language and version. The default behavior is described as follows:

| Agent Language | JDK Version      | Default Implementation |
|----------------|------------------|------------------------|
| Python         | Any              | Python SDK  |
| Java           | JDK 17+          | Java SDK    |
| Java           | JDK 16 and below | Python SDK  |


As shown in the table above, for Java agents running on JDK 17+, the framework automatically uses the Java SDK implementation. If you need to use the Python SDK instead (not recommended), you can set the `lang` parameter to `"python"` in the `@MCPServer` annotation:
```java
@MCPServer(lang = "python")
public static ResourceDescriptor myMcp() {
    // ...
}
```