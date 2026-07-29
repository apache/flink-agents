---
title: Tool Use
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
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
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
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
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
    "notify_shipping_manager", ResourceType.TOOL, Tool.from_callable(notify_shipping_manager)
)

...

# Create react agent with notify shipping manager tool.
review_analysis_react_agent = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
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
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
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

## Tool Parameter Injection

Some tools need runtime data that should not be chosen by the model, such as a tenant id, account id, request trace id, or other framework-owned context. Mark those parameters as injected so they are hidden from the model-facing tool schema, and declare where the runtime should read each value.

The model only sees and provides normal tool parameters. The injected parameters are merged into the tool call immediately before the built-in `tool_call_action` executes the tool.

{{< tabs "Inject Tool Parameters" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.agents import Agent
from flink_agents.api.decorators import tool
from flink_agents.api.tools import InjectedArg


class OrderAgent(Agent):

    @tool(injected_args={"tenant_id": InjectedArg.from_config("tenant_id")})
    @staticmethod
    def query_order(order_id: str, tenant_id: str) -> str:
        """Query an order.

        Parameters
        ----------
        order_id : str
            The order id to query.
        """
        return query_order_from_store(order_id=order_id, tenant_id=tenant_id)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class OrderAgent extends Agent {

    @Tool(description = "Query an order.")
    public static String queryOrder(
            @ToolParam(name = "order_id") String orderId,
            @ToolParam(
                    name = "tenant_id",
                    injected = true,
                    source = ToolParameterSource.CONFIG,
                    key = "tenant_id")
                    String tenantId) {
        return queryOrderFromStore(orderId, tenantId);
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Configure the injected value on the agent execution environment. Python uses `agents_env.get_config().set_str("tenant_id", "tenant-a")`; Java uses `agentsEnv.getConfig().setStr("tenant_id", "tenant-a")`.

Injected values can also come from memory when the value is attached to the current request or session instead of static environment configuration:

{{< tabs "Inject Tool Parameters From Memory" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.decorators import tool
from flink_agents.api.tools import InjectedArg


@tool(
    injected_args={
        "trace_id": InjectedArg.from_sensory_memory("request.trace_id"),
    }
)
def lookup_order(order_id: str, trace_id: str) -> str:
    return query_order_with_trace(order_id=order_id, trace_id=trace_id)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Tool(description = "Look up an order.")
public static String lookupOrder(
        @ToolParam(name = "order_id") String orderId,
        @ToolParam(
                name = "trace_id",
                injected = true,
                source = ToolParameterSource.SENSORY_MEMORY,
                key = "request.trace_id")
                String traceId) {
    return queryOrderWithTrace(orderId, traceId);
}
```
{{< /tab >}}

{{< /tabs >}}

The supported sources are `config`, `sensory_memory`, and `short_term_memory`.
If `source` is omitted, it defaults to `sensory_memory`.
For Java annotation-based tools, `@ToolParam(injected = true)` is what marks the
parameter as framework-injected and hidden from the model; `source` and `key` only
configure where the value is read from.
If the same injected parameter is declared both on the tool function and by a
descriptor such as YAML, the declarations must resolve to the same source and key;
conflicting declarations fail during agent plan construction.

Injected parameters are part of the tool execution contract, not the model contract:

- They are not included in the JSON schema sent to the model.
- They are not written back to the original `ToolRequestEvent`.
- If a model supplies an argument with the same name, the injected value wins during execution.

## MCP Tool

See [MCP]({{< ref "docs/development/mcp" >}}) for details.

## Built-in Events and Actions

The built-in `tool_call_action` listens to `ToolRequestEvent`. For each tool call, it looks up the tool resource by function name, executes it through durable execution, and records whether it succeeded. After all tool calls in the batch have been processed, it sends a `ToolResponseEvent`.

When the tool request comes from `chat_model_action`, the emitted `ToolResponseEvent` is automatically consumed by `chat_model_action` to continue the chat. See [Built-in Events and Actions in Chat Models]({{< ref "docs/development/chat_models#built-in-events-and-actions" >}}) for details on how `chat_model_action` handles tool responses.

Users can also send `ToolRequestEvent` directly when they want to invoke tools programmatically.
