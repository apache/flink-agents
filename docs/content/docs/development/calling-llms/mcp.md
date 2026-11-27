---
title: "MCP"
weight: 6
type: docs
aliases:
  - /docs/development/mcp/
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

MCP tools and prompts are discovered when the operator starts, including for
internal sub-agents. Building or serializing an agent plan does not contact the
server. This also applies to MCP servers declared in YAML.

Discovery fails startup if a server cannot respond within its configured
timeout (and Java retry policy), or if it advertises a duplicate tool/prompt
name. Tool names beginning with `_subagent_` are reserved and are rejected.
The operator closes discovered servers when it stops, even when a server has
no tools or prompts. Changes to a server's advertised resources are picked up
on the next operator start; they are not refreshed while the operator runs.

## Declare MCP Server in Agent

Register the server as an `MCP_SERVER` Resource. The example below uses the
Declaration API with a Python decorator or Java annotation; see
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for the common registration methods and scopes.

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
                    .addInitialArgument("timeout", Duration.ofSeconds(30))
                    .build();
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- The examples use the Declaration API to register an MCP server connection:
  - In Python, use `@mcp_server`.
  - In Java, use `@MCPServer`.
- Use the builder pattern in Java to configure the MCP server with endpoint, timeout, headers, and authentication

`timeout` (default `30 s`) and Python's `sse_read_timeout` (default `5 min`) accept positive values in [duration format]({{< ref "docs/operations/configuration#duration-values" >}}). Integers and unitless integer strings are interpreted as milliseconds.

### Authentication

MCP servers can be configured with authentication:

{{< tabs "MCP Server Authentication" >}}

{{< tab "Python" >}}
```python
from flink_agents.integrations.mcp.auth import (
    ApiKeyAuth,
    BasicAuth,
    BearerTokenAuth,
)

@mcp_server
@staticmethod
def authenticated_mcp_server() -> ResourceDescriptor:
    """Connect to MCP server with authentication."""
    # Using Bearer Token Authentication
    return ResourceDescriptor(clazz=ResourceName.MCP_SERVER,
                              endpoint="http://api.example.com/mcp",
                              auth=BearerTokenAuth(token="your-oauth-token"))

    # Or using Basic Authentication
    # auth=BasicAuth(username="username", password="password")

    # Or using API Key Authentication
    # auth=ApiKeyAuth(header_name="X-API-Key", api_key="your-api-key")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@MCPServer
public static ResourceDescriptor authenticatedMcpServer() {
    // Using Bearer Token Authentication
    return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", "http://api.example.com/mcp")
                    .addInitialArgument("timeout", Duration.ofSeconds(30))
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
- `CustomAuth` (Python only) - For any other `httpx.Auth`, such as the MCP SDK's `OAuthClientProvider`

A Python MCP server stores `auth` in the agent plan as plain JSON, using the
same field names as the Java classes. In YAML, give a Python server's `auth` as
a mapping with an `authType` key:

```yaml
mcp_servers:
  - name: my_mcp
    clazz: mcp
    endpoint: http://api.example.com/mcp
    auth:
      authType: bearer        # or: basic (username, password),
      token: your-oauth-token #     api_key (headerName, apiKey)
```

In Python, `CustomAuth` names a factory as `<module>:<qualname>` plus JSON
keyword arguments. The factory must be importable where the agent runs:

```python
# my_pkg/auth.py
import httpx
from mcp.client.auth import OAuthClientProvider

def make_oauth(server_url: str) -> httpx.Auth:
    return OAuthClientProvider(server_url=server_url, ...)

# In the agent
from flink_agents.integrations.mcp.auth import CustomAuth

auth=CustomAuth(factory="my_pkg.auth:make_oauth",
                arguments={"server_url": "https://mcp.example.com/mcp"})
```

- The factory is imported and called when the server first connects, so it must
  name trusted code, like a resource's `clazz`.
- Each MCP server instance calls the factory once and reuses the returned
  `httpx.Auth` for its later connections. Parallel subtasks build their own
  instances, possibly in different processes, so an OAuth provider that should
  not authorize once per subtask needs token storage outside the process.
- A string `auth` value, or an `httpx.Auth` instance passed directly, is
  rejected.

### Retries

Remote calls to an MCP server (listing/calling tools and prompts) are retried with exponential backoff on transient failures. You can tune the retry behavior per server with the following descriptor arguments. {{< hint info >}}Note: retry is currently only supported by the [Java SDK](#mcp-sdk).{{< /hint >}}

| Argument           | Default | Description                                          |
|--------------------|---------|------------------------------------------------------|
| `maxRetries`       | 3       | Maximum number of retries for a failed remote call.  |
| `initialBackoff` | `100 ms` | Initial retry delay in [duration format]({{< ref "docs/operations/configuration#duration-values" >}}). Integers and unitless integer strings are interpreted as milliseconds. |
| `maxBackoff` | `10 s` | Maximum retry delay in [duration format]({{< ref "docs/operations/configuration#duration-values" >}}). Integers and unitless integer strings are interpreted as milliseconds. |

```java
@MCPServer
public static ResourceDescriptor myMcp() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                .addInitialArgument("endpoint", MCP_ENDPOINT)
                .addInitialArgument("timeout", Duration.ofSeconds(30))
                .addInitialArgument("maxRetries", 5)
                .addInitialArgument("initialBackoff", Duration.ofMillis(200))
                .addInitialArgument("maxBackoff", Duration.ofSeconds(10))
                .build();
}
```

## Use MCP prompts and tools in Agent


MCP prompts and tools are managed by external MCP servers and automatically discovered when you define an MCP server connection in your agent.

The example assumes that the server at `http://127.0.0.1:8000/mcp` provides a
`review_analysis_prompt` prompt and a `notify_shipping_manager` tool. Reference
the exact names exposed by the server in both Java and Python; Flink Agents does
not convert them to match language naming conventions.

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
                    .addInitialArgument("timeout", Duration.ofSeconds(30))
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
                .addInitialArgument("tools", Collections.singletonList("notify_shipping_manager"))
                .build();
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- All tools and prompts from the MCP server are automatically registered.
- Reference MCP prompts and tools by their names, like reference [local prompt]({{< ref "docs/development/calling-llms/prompts#using-prompts-in-agents" >}}) and [function tool]({{< ref "docs/development/calling-llms/tool_use#define-tool-as-static-method-in-agent-class" >}}) .

## Appendix

### MCP SDK

Flink Agents offers two implementations of MCP support, based on MCP SDKs in different languages (Python and Java). Typically, users do not need to be aware of this, as the framework automatically determines the appropriate implementation based on the language and version. The default behavior is described as follows:

| Agent Language | JDK Version      | Default Implementation |
|----------------|------------------|------------------------|
| Python         | Any              | Python SDK  |
| Java           | JDK 17+          | Java SDK    |
| Java           | JDK 16 and below | Python SDK  |


When the selected implementation uses Python, install its Python dependencies and
configure the Python environment even for a Java Flink job; see
[dependency setup]({{< ref "docs/development/create-agent/choose-apis-and-languages#integrate-the-agent-into-a-flink-job" >}}).

As shown in the table above, for Java agents running on JDK 17+, the framework automatically uses the Java SDK implementation. If you need to use the Python SDK instead (not recommended), you can set the `lang` parameter to `"python"` in the `@MCPServer` annotation:
```java
@MCPServer(lang = "python")
public static ResourceDescriptor myMcp() {
    // ...
}
```
