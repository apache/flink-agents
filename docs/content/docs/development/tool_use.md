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

Developer can define the tool as a local Python function, and use it in either workflow agent or react agent. 
- For workflow agent, developer defines the tool as a static method in the agent class, and use the `@tool` annotation to mark the method as a tool. 
- For react agent, you have to register the tool to the execution environment, and then pass the tool name to the chat model descriptor when creating the ReAct agent.

{{< hint info >}}
Flink Agents uses the docstring of the tool function to generate the tool metadata. The docstring of the python function should accurately describe the tool's purpose, parameters, and return value, so that the LLM can understand the tool and use it effectively.
{{< /hint >}}

Below is an example of how to define the tool as a local Python function in workflow agent:

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
    
    ...
```

Below is an example of how to define the tool as a local Python function in react agent:

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
        tools=["notify_shipping_manager"],
    ),
    ...
)
```

## How to Integrate with MCP Server

Flink Agents supports integrating with a remote MCP server to use the resources provided by the MCP server, including tools and prompts.

{{< hint warning >}}
**TODO**: How to integrate with MCP Server.
{{< /hint >}}

### MCP Tools

{{< hint warning >}}
**TODO**: How to use the tools provided by MCP Server.
{{< /hint >}}

### MCP Prompt

{{< hint warning >}}
**TODO**: How to use the prompts provided by MCP Server.
{{< /hint >}}

## Built-in Events for Tool

Flink Agents provides built-in events for tool call request and tool call response, specifically `ToolRequestEvent` and `ToolResponseEvent`. By default, Flink Agents built-in action will listen to these events and handle the tool call request and tool call response automatically. If you have special needs, you can also define your own action to listen to these events and handle the `ToolRequestEvent` and `ToolResponseEvent` accordingly.

Here is the definition of the `ToolRequestEvent` and `ToolResponseEvent`:

```python
class ToolRequestEvent(Event):
    """Event representing a tool call request.

    Attributes:
    ----------
    model: str
        name of the model that generated the tool request.
    tool_calls : List[Dict[str, Any]]
        tool calls that should be executed in batch.
    """

    model: str
    tool_calls: List[Dict[str, Any]]

  
class ToolResponseEvent(Event):
    """Event representing a result from tool call.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    responses : Dict[UUID, Any]
        The dict maps tool call id to result.
    external_ids : Dict[UUID, str]
        Optional identifier for storing original tool call IDs from external systems
        (e.g., Anthropic tool_use_id).
    """

    request_id: UUID
    responses: Dict[UUID, Any]
    external_ids: Dict[UUID, str | None]
```
