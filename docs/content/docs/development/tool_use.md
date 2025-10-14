---
title: Tool Use
weight: 7
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

Flink Agents provides a flexible and extensible tool use mechanism. Developers can define the tool as a local Python function, or they can integrate with a remote MCP server to use the tools provided by the MCP server.

## Local Function as Tool

Developer can define the tool as a local Python/Java function, and there are two ways to define and register a local function as a tool:

{{< hint info >}}
Flink Agents uses the docstring of the python tool function to generate the tool metadata. The docstring of the python function should accurately describe the tool's purpose, parameters, and return value, so that the LLM can understand the tool and use it effectively.
{{< /hint >}}

### Define Tool as Static Method in Agent Class

Developer can define the tool as a static method in the agent class while defining the workflow agent, and use the `@tool` decorator to mark the function as a tool in python (or `@Tool` annotation in java). The tool can be referenced by its name in the `tools` list of the `ResourceDescriptor` when creating the chat model in the agent.

{{< tabs "Define Tool as Static Method in Agent Class" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):

    @tool
    @staticmethod
    def notify_shipping_manager(id: str, review: str) -> None:
        """Notify the shipping manager when product received a negative review due to
        shipping damage.

        Parameters
        ----------
        id : str
            The id of the product that received a negative review due to shipping damage
        review: str
            The negative review content
        """
        notify_shipping_manager(id=id, review=review)

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> ResourceDescriptor:
        """ChatModel which focus on review analysis."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            ...,
            tools=["notify_shipping_manager"], # reference the tool by its name
        )
    
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {
    
    @Tool(description = "Notify the shipping manager when product received a negative review due to shipping damage.")
    public static void notifyShippingManager(
            @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
        CustomTypesAndResources.notifyShippingManager(id, review);
    }
    
    @ChatModelSetup
    public static ResourceDescriptor reviewAnalysisModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaChatModelConnection")
                ...
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager")) // reference the tool by its name
                .build();
    }
    
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use `@tool` decorator to define the tool in python (or `@Tool` annotation in java)
- Reference the tool by its name in the `tools` list of the `ResourceDescriptor`


### Register Tool to Execution Environment

Developer can register the tool to the execution environment, and then reference the tool by its name. This allows the tool to be reused by multiple agents.

{{< tabs "Register Tool to Execution Environment" >}}

{{< tab "Python" >}}
```python
def notify_shipping_manager(id: str, review: str) -> None:
    """Notify the shipping manager when product received a negative review due to
    shipping damage.

    Parameters
    ----------
    id : str
        The id of the product that received a negative review due to shipping damage
    review: str
        The negative review content
    """
    ...

...

# Add notify shipping manager tool to the execution environment.
agents_env.add_resource(
    "notify_shipping_manager", Tool.from_callable(notify_shipping_manager)
)

...

# Create react agent with notify shipping manager tool.
review_analysis_react_agent = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=OllamaChatModelSetup,
        tools=["notify_shipping_manager"], # reference the tool by its name
    ),
    ...
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Tool(description = "Notify the shipping manager when product received a negative review due to shipping damage.")
public static void notifyShippingManager(
        @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
    ...
}

// Add notify shipping manager tool to the execution environment.
agentsEnv
        .addResource(
                "notifyShippingManager",
                ResourceType.TOOL,
                org.apache.flink.agents.api.tools.Tool.fromMethod(
                        ReActAgentExample.class.getMethod(
                        "notifyShippingManager", String.class, String.class)));

// Create react agent with notify shipping manager tool.
ReActAgent reviewAnalysisReactAgent = new ReActAgent(
        ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument(
                        "tools", Collections.singletonList("notifyShippingManager")) // reference the tool by its name
                ...
                .build(),
        ...);
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use `AgentsExecutionEnvironment.add_resource` to register the tool to the execution environment
- Reference the tool by its name in the `tools` list of the `ResourceDescriptor`

## MCP Tool

{{< hint info >}}
MCP (Model Context Protocol) is a standardized protocol for integrating AI applications with external data sources and tools. MCP tools allow dynamic tool retrieval from MCP servers.
{{< /hint >}}

{{< hint warning >}}
MCP Tool is only supported in python currently.
{{< /hint >}}

MCP tools are managed by external MCP servers and automatically discovered when you define an MCP server connection in your agent.

### Define MCP Server with Tools

Create an MCP server that exposes tools using the `FastMCP` library:

```python
# mcp_server.py
mcp = FastMCP("ReviewServer")

@mcp.tool()
async def notify_shipping_manager(id: str, review: str) -> None:
    """Notify the shipping manager when product received a negative review due to
    shipping damage.

    Parameters
    ----------
    id : str
        The id of the product that received a negative review due to shipping damage
    review: str
        The negative review content
    """
    ...

mcp.run("streamable-http")
```

**Key points:**
- Use `@mcp.tool()` decorator to define tools
- The function name becomes the tool identifier

### Use MCP Tools in Agent

Connect to the MCP server and use its tools in your agent:

```python
class ReviewAnalysisAgent(Agent):
    ...

    @mcp_server
    @staticmethod
    def review_mcp_server() -> MCPServer:
        """Connect to MCP server."""
        return MCPServer(endpoint="http://127.0.0.1:8000/mcp")

    @chat_model_setup
    @staticmethod
    def review_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_server",
            model="qwen3:8b",
            tools=["notify_shipping_manager"],  # Reference MCP tool by name
        )
```

**Key points:**
- Use `@mcp_server` decorator to define MCP server connection
- Reference MCP tools by their function name (e.g., `"notify_shipping_manager"`)
- All tools from the MCP server are automatically registered