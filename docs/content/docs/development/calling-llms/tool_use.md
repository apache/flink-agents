---
title: "Tool Use"
weight: 5
type: docs
aliases:
  - /docs/development/tool_use/
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

Tools let a model request work from a Java or Python function, or from an
[MCP server]({{< ref "docs/development/calling-llms/mcp" >}}). This page explains how
to declare function tools, supply runtime parameters, and configure tool execution.
For the chat request, tool-call, and response flow, see
[Tool Calling in Chat with Models]({{< ref "docs/development/calling-llms/chat#built-in-events-and-actions" >}}).

## Local Function as Tool

Function tools are `TOOL` Resources. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration methods and scopes. The examples below show declarations on an
Agent and registration on the execution environment for sharing across Agents.
In either case, reference the registered tool name in the chat model setup's `tools` list.

The following snippets show the tool declaration and registration parts of a job.
They assume an Ollama connection has been registered using the
[Programmatic API]({{< ref "docs/development/create-agent/resources#programmatic-api" >}}),
with `ollamaChatModelConnection` as its registered name to match the references below.
The notification helper is application code; the complete
{{< gh_link file="python/flink_agents/examples/quickstart/react_agent_example.py" name="Python example" >}}
and {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ReActAgentExample.java" name="Java example" >}}
show how to connect a tool to a ReAct Agent and a Flink job.

{{< hint info >}}
Flink Agents uses a Python function's docstring to describe the tool to the model.
Describe its purpose, parameters, and return value accurately. In Java, use
`@Tool(description = "...")` and `@ToolParam` to describe the function and name its parameters.
{{< /hint >}}

### Define Tool as Static Method in Agent Class

Use `@tool` in Python or `@Tool` in Java to declare a static method as a tool.
The chat model setup references that method by name:

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
            connection="ollamaChatModelConnection",
            model="qwen3:8b",
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
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager")) // reference the tool by its name
                .build();
    }
}
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use `@tool` decorator to define the tool in python (or `@Tool` annotation in java)
- Reference the tool by its name in the `tools` list of the `ResourceDescriptor`


### Register Tool to Execution Environment

Register a tool on the execution environment when multiple agents need it.
Each agent references the registered resource name in its chat model setup.

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
        connection="ollamaChatModelConnection",
        model="qwen3:8b",
        tools=["notify_shipping_manager"], # reference the tool by its name
    ),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Tool(description = "Notify the shipping manager when product received a negative review due to shipping damage.")
public static void notifyShippingManager(
        @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
    CustomTypesAndResources.notifyShippingManager(id, review);
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
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument(
                        "tools", Collections.singletonList("notifyShippingManager")) // reference the tool by its name
                .build(),
        null,
        null);
```
{{< /tab >}}

{{< /tabs >}}

**Key points:**
- Use `agents_env.add_resource` (Python) or `agentsEnv.addResource` (Java) to register the tool on the execution environment
- Reference the tool by its name in the `tools` list of the `ResourceDescriptor`

### Argument Validation

Function tools validate arguments before execution. Unknown fields, incorrect types,
and violated constraints produce a failed tool response without running the function.
Validation uses the complete function signature, including injected parameters.
Numeric strings and booleans are not converted to numbers.

Missing arguments use declared defaults. Explicit `null` does not select a default
and is accepted only for nullable parameters. Framework values override model-supplied values for
[injected parameters](#tool-parameter-injection). Missing or invalid framework values
fail the call; model-supplied values are never used as a fallback.

In Python, use type annotations and `Annotated[..., Field(...)]` for constraints:

```python
from typing import Annotated
from pydantic import Field

@tool
def search(query: Annotated[str, Field(min_length=1)],
           limit: Annotated[int, Field(ge=1, le=100)] = 10) -> str:
    return query[:limit]
```

In Java, use `@ToolParam`. `defaultValue` is JSON: `"10"` for a number,
`"null"` for null, and `"\"text\""` for a string. Set `nullable = true` to accept
null; `required = false` needs a default or `nullable = true` (defaulting to null).

```java
@Tool(description = "Search documents")
public static String search(
        @ToolParam(name = "query", minLength = 1) String query,
        @ToolParam(name = "limit", defaultValue = "10", minimum = "1", maximum = "100")
                int limit) {
    return query.substring(0, Math.min(query.length(), limit));
}
```

## Tool Parameter Injection

Some tools need runtime data that should not be chosen by the model, such as a tenant id, account id, request trace id, or other framework-owned context. Mark those parameters as injected so they are hidden from the model-facing tool schema, and declare where the runtime should read each value.

The model only sees and provides normal tool parameters. The built-in `tool_call_action` resolves injected values and overwrites any model-supplied values for the same parameters before calling the tool.

Choose the source according to where the value is available when the tool runs:

| Source | Runtime value | Python declaration |
| --- | --- | --- |
| `config` | A value in the agent execution environment configuration | `InjectedArg.from_config("tenant_id")` |
| `sensory_memory` | A value at a path in the current run's sensory memory | `InjectedArg.from_sensory_memory("request.trace_id")` |
| `short_term_memory` | A value at a path in the agent's short-term memory | `InjectedArg.from_short_term_memory("session.tenant_id")` |

For memory sources, write the value to the selected path before requesting the
tool call. The path must exist and refer to a value, rather than a nested memory
object. The injection key identifies the configuration entry or memory path;
it can differ from the tool parameter name.

In Java annotations, set `@ToolParam(injected = true)` to hide the parameter from
the model, and use `source` and `key` to bind its value. The default source is
`sensory_memory`; an omitted key uses the parameter name. For YAML declarations,
see the [`injected_args` reference]({{< ref "docs/development/create-agent/yaml#resources" >}}),
which covers the configuration shape, defaults, and examples.

For the following example, set `tenant_id` on the agent execution environment
with `agents_env.get_config().set_str("tenant_id", "tenant-a")` in Python or
`agentsEnv.getConfig().setStr("tenant_id", "tenant-a")` in Java.

{{< tabs "Inject Tool Parameters" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
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

The model supplies only `order_id`; the framework supplies `tenant_id`.

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

### Overrides and failures

The built-in `tool_call_action` resolves injection before invoking a function tool:

- Injected parameters are excluded from the model-facing JSON schema.
- A framework value overrides any model-supplied value for the same parameter.
  The original `ToolRequestEvent` arguments remain unchanged.
- A missing configuration entry, missing memory path, or invalid injected value
  fails the tool call without invoking the function. Model-supplied values are
  never used as a fallback.
- If the function and a descriptor such as YAML both declare an injected
  parameter, their source and key must agree. A conflict fails during agent plan
  construction, before the tool can run.

This resolution is part of the built-in tool-call flow. When calling a function
tool directly, supply its required arguments yourself.

## Parallel tool-call batches

A model may request several tool calls in one `ToolRequestEvent`. The built-in
`tool_call_action` can execute the tools registered by the agent, possibly
including ordinary functions or MCP tool calls, as parallel operations. Use the
following settings on the agent execution environment:

| Option | Default | Effect on tool execution |
| --- | --- | --- |
| `tool-call.async` | `true` | Enables durable async tool calls. With `false`, tools execute synchronously, one at a time. |
| `async.batch.parallelism` | Available CPU count | Maximum concurrent calls in one async batch. `1` executes tools one at a time while keeping async execution enabled. |

For example, allow up to four concurrent calls per batch:

{{< tabs "Configure Tool Parallelism" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.core_options import AgentExecutionOptions

agents_env.get_config().set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
agents_env.get_config().set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 4)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.agents.AgentExecutionOptions;

agentsEnv.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, true);
agentsEnv.getConfig().set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 4);
```
{{< /tab >}}
{{< /tabs >}}

With async execution enabled and `async.batch.parallelism` greater than `1`,
multiple tool calls can run concurrently. This limit also applies to batches
created with `ctx.gather(...)`. The `num-async-threads` setting further limits
available concurrency. Tool batches share execution capacity with other external
calls, so a large batch may delay other model calls or retrieval requests. Lower
the batch limit to leave capacity for other calls or avoid overwhelming an
external service. Actual concurrency can be lower than the batch limit.

{{< hint warning >}}
Calls in a parallel batch must be independent: a tool must not wait for another
call in the same batch to complete. Concurrency is an optimization and does not
guarantee that calls overlap or finish in request order. If one operation depends
on another's result, request it in a later step instead of the same batch.
{{< /hint >}}

### Timeouts and external effects

`async.batch.timeout.ms` sets an overall timeout for a parallel batch of ordinary
tool calls. Its default is `-1`; non-positive values disable it. When it expires,
completed calls keep their results and unfinished calls are reported to the model
as failed tool calls.

For requests containing only ordinary tools, the batch timeout is not used when
`tool-call.async` is `false`, when `async.batch.parallelism` is `1` or less, or when
the request has a single tool call. Configure timeouts for blocking operations
inside each tool, such as an HTTP client read timeout.

A batch timeout does not undo external effects or guarantee that an already
running tool stops. Tools that change external systems should be idempotent,
because a call may run again after recovery. See
[Configuration]({{< ref "docs/operations/configuration#runtime-and-async-execution" >}}) for the full async batch
and execution-capacity options, and
[Consistency]({{< ref "docs/operations/consistency#recovery-guarantees-and-user-responsibilities" >}})
for recovery guarantees.

## Tool response content and metadata

For simple tools, return a value and the framework converts it to response text.
Strings are used directly. Other values, such as maps and lists, are represented
as JSON text when possible; otherwise, their string representation is used.
For example, a Python tool can return a `date` directly and the model receives
text such as `2026-01-01`.

Binary values (`byte[]` in Java; `bytes` and `bytearray` in Python) are represented
as Base64 strings in the JSON text, including when nested in maps or lists.
For example, the byte `0xff` produces the response text `"/w=="`.
To send images, audio, or other media to the model, return media blocks in a
`ToolResponse` instead of returning raw binary data.

Return a `ToolResponse` when you want to choose the content sent to the model or
attach additional data for your application:

- `blocks` contains the response content in order. It accepts text and media
  blocks; reasoning, tool calls, and nested tool results are not supported.
- `metadata` holds application data, such as a record ID, that is not sent to the
  model. Use JSON-serializable values when the response needs to be persisted.

For example, this tool returns a weather description for the model and a station
ID for application code:

{{< tabs "tool-response" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import TextBlock
from flink_agents.api.decorators import tool
from flink_agents.api.tools import ToolResponse

# Declare this method inside an Agent class.
@tool
@staticmethod
def get_weather(city: str) -> ToolResponse:
    """Look up the weather.

    Args:
        city: City to query.
    """
    return ToolResponse(
        blocks=[TextBlock(text=f"The temperature in {city} is 25°C.")],
        metadata={"station_id": "station-123"},
    )

# Convenience factories:
# ToolResponse.text("Operation completed")
# ToolResponse.error("The station could not be reached")
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.tools.ToolResponse;

import java.util.Map;

@Tool(description = "Look up the weather")
public static ToolResponse getWeather(@ToolParam(name = "city") String city) {
    return ToolResponse.text("The temperature in " + city + " is 25°C.")
            .withMetadata(Map.of("station_id", "station-123"));
}
```
{{< /tab >}}
{{< /tabs >}}

Application code can read `metadata` without parsing the response text. Use
`ToolResponse.error("reason")` to report an operation that could not be completed;
the model receives the error description as the tool result.

### Representing media returned by tools

{{< hint warning >}}
Current model integrations do not support sending media returned by tools to the
model. A tool response containing media causes the chat request to fail, even if
the model supports media in user messages. Use text-only tool responses in chat
workflows for now.
{{< /hint >}}

Media must be provided explicitly as blocks. Returning bytes or a URL as an
ordinary value does not identify it as an image or another media type. For
example, given PNG bytes in `image_bytes` / `imageBytes`:

{{< tabs "tool-response-media" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import ImageBlock, TextBlock
from flink_agents.api.tools import ToolResponse

response = ToolResponse(
    blocks=[
        TextBlock(text="Generated chart:"),
        ImageBlock.from_bytes("image/png", image_bytes),
    ],
    metadata={"report_id": "report-123"},
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.tools.ToolResponse;

import java.util.List;
import java.util.Map;

ToolResponse response = ToolResponse.success(
        List.of(new TextBlock("Generated chart:"),
                ImageBlock.fromBytes("image/png", imageBytes)))
        .withMetadata(Map.of("report_id", "report-123"));
```
{{< /tab >}}
{{< /tabs >}}

## MCP Tool

See [MCP]({{< ref "docs/development/calling-llms/mcp" >}}) for details.

## Built-in Events and Actions

The built-in `tool_call_action` listens to `ToolRequestEvent`. For each tool call, it looks up the tool resource by function name, executes it through durable execution, and records whether it succeeded. After all tool calls in the batch have been processed, it sends a `ToolResponseEvent`.

Ordinary tool return values are converted to successful text responses as described in [Tool response content and metadata](#tool-response-content-and-metadata). A tool that completes normally but cannot perform the requested operation can return `ToolResponse.error("reason")`; an exception still represents an invocation failure. Both failure forms are recorded as failed tool calls in `ToolResponseEvent`.

When the tool request comes from `chat_model_action`, that Action automatically handles the emitted `ToolResponseEvent` to continue the chat. See [Tool Calling in Chat with Models]({{< ref "docs/development/calling-llms/chat#built-in-events-and-actions" >}}) for details on how `chat_model_action` handles tool responses.

Users can also send `ToolRequestEvent` directly when they want to invoke tools programmatically.
