---
title: Tool Use
weight: 10
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
- If a model supplies an argument with the same name, the framework-provided value takes precedence.

## Parallel tool-call batches

When `tool-call.async` is enabled, the built-in `tool_call_action` runs all tool calls from one
`ToolRequestEvent` as a single durable batch. Set `async.batch.parallelism` to control concurrency:

- `1` — serial execution (one tool at a time).
- `> 1` — parallel batch with a sliding window of at most that many in-flight tool calls.

Java and Python support concurrent tool-call batches on all supported JDK versions.

Tool batches share a `num-async-threads` pool **per operator subtask** (every key routed to
that subtask). On JDK 21+ and in Python, chat and RAG calls also use this pool. On older JDKs,
Java batches use a separate pool from the Action workers. The default batch parallelism is
the host CPU count.

Built-in actions for a **single key** run one at a time. In the usual chat → tool path, a chat
async call finishes before `tool_call_action` starts, so they do not overlap on the same key.
Contention appears mainly **across keys** on the same subtask: one key's parallel tool batch can
delay another key's chat or RAG async work.

{{< hint warning >}}
**Cross-key impact:** with defaults (`num-async-threads = 2× cores`, `async.batch.parallelism =
cores`), one full tool batch can use up to half the subtask pool; several hot keys can saturate it.
If your job mixes heavy tool batches with chat/RAG on the same subtask, lower
`async.batch.parallelism` or increase `num-async-threads`.
{{< /hint >}}

`async.batch.timeout.ms` applies to the whole batch. On timeout, completed slots keep their
outcome; slots that started but did not finish are recorded as failures; slots that never started
executing (for example, queued in a saturated pool) stay pending, so they are re-executed after
recovery instead of recording a false failure. Timeout cancellation is best-effort; side-effecting
tools should be idempotent or provide a reconciler.

{{< hint warning >}}
**Thread reclamation:** a timeout unblocks the batch but cannot interrupt a tool that is still
running. Its worker thread stays in the shared `num-async-threads` pool until the tool returns on
its own, so a tool that never returns permanently reduces pool capacity. Bound blocking work inside
the tool (for example an HTTP client read timeout) rather than relying on this batch timeout to free
the thread.
{{< /hint >}}

## Tool response content and metadata

For simple tools, return a value and the framework converts it to response text.
Strings are used directly. Other values, such as maps and lists, are represented
as JSON text when possible; otherwise, their string representation is used.
For example, a Python tool can return a `date` directly and the model receives
text such as `2026-01-01`.

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

See [MCP]({{< ref "docs/development/mcp" >}}) for details.

## Built-in Events and Actions

The built-in `tool_call_action` listens to `ToolRequestEvent`. For each tool call, it looks up the tool resource by function name, executes it through durable execution, and records whether it succeeded. After all tool calls in the batch have been processed, it sends a `ToolResponseEvent`.

Ordinary tool return values are converted to successful text responses as described in [Tool response content and metadata](#tool-response-content-and-metadata). A tool that completes normally but cannot perform the requested operation can return `ToolResponse.error("reason")`; an exception still represents an invocation failure. Both failure forms are recorded as failed tool calls in `ToolResponseEvent`.

When the tool request comes from `chat_model_action`, the emitted `ToolResponseEvent` is automatically consumed by `chat_model_action` to continue the chat. See [Built-in Events and Actions in Chat Models]({{< ref "docs/development/chat_models#built-in-events-and-actions" >}}) for details on how `chat_model_action` handles tool responses.

Users can also send `ToolRequestEvent` directly when they want to invoke tools programmatically.
