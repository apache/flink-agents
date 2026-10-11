---
title: ReAct Agent
weight: 2
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

ReAct Agent is a general paradigm that combines reasoning and action capabilities to solve complex tasks. Leveraging this paradigm, the user only needs to specify the goal with prompt and provide available tools, and the LLM will decide how to achieve the goal and take actions autonomously.

{{< hint info >}}
For guidance on choosing Java or Python, see [Should I choose Java or Python?]({{< ref "docs/faq/faq#q3-should-i-choose-java-or-python" >}}).
{{< /hint >}}

{{< hint info >}}
The snippets on this page are fragments that each focus on a single concept. For an end-to-end runnable walkthrough, see the [ReAct Agent Quickstart]({{< ref "docs/get-started/quickstart/react_agent" >}}) and the full source: [`react_agent_example.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/react_agent_example.py) (Python) and [`ReActAgentExample.java`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/ReActAgentExample.java) (Java).
{{< /hint >}}

The snippets below assume the following imports:

{{< tabs "ReAct Agent Imports" >}}

{{< tab "Python" >}}
```python
from pydantic import BaseModel
from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import Tool
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;

import java.util.Arrays;
import java.util.List;
```
{{< /tab >}}

{{< /tabs >}}

## ReAct Agent Example

{{< tabs "ReAct Agent Example" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=my_prompt,
    output_schema=MyBaseModelDataType, # or output_schema=my_row_type_info
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent myReActAgent =
        new ReActAgent(
                chatModelDescriptor,
                myPrompt,
                MyBaseModelDataType.class
                // or myRowTypeInfo
        );
```
{{< /tab >}}

{{< /tabs >}}

## Initialize Arguments
### Chat Model
User should specify the chat model used in the ReAct Agent.

We use `ResourceDescriptor` to describe the chat model, includes chat model type and chat model arguments. See [Chat Model]({{< ref "docs/development/chat_models" >}}) for more details.

{{< tabs "ChatModel ResourceDescriptor" >}}

{{< tab "Python" >}}
```python
chat_model_descriptor = ResourceDescriptor(
    clazz=ResourceName.ChatModel.OLLAMA_SETUP,
    connection="my_ollama_connection",
    model="qwen3:8b",
    tools=["my_tool1", "my_tool2"],
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ResourceDescriptor chatModelDescriptor =
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "myOllamaConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .addInitialArgument(
                                "tools", List.of("myTool1", "myTool2"))
                        .build();
```
{{< /tab >}}

{{< /tabs >}}

{{< hint warning >}}
The tools listed in `tools=[...]` must be registered on the `AgentsExecutionEnvironment` **before** the agent runs, using a name that matches the entry in the list — otherwise the agent fails at runtime with a "resource not found" error.
{{< /hint >}}

Register the referenced tools (this can also be done with a YAML file — see [Tool Use]({{< ref "docs/development/tool_use#register-tool-to-execution-environment" >}}) for the full guide):

{{< tabs "Register Tools" >}}

{{< tab "Python" >}}
```python
agents_env.add_resource(
    "my_tool1", ResourceType.TOOL, Tool.from_callable(my_tool1)
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
agentsEnv.addResource(
        "myTool1",
        ResourceType.TOOL,
        Tool.fromMethod(MyAgentExample.class.getMethod("myTool1", String.class)));
```
{{< /tab >}}

{{< /tabs >}}

### Prompt
User can provide prompt to instruct agent.

A typical prompt contains two messages: a SYSTEM message that tells the agent what to do (and gives input and output examples), and a USER message that describes how to convert the input element into a text string. This is a recommended pattern, not a strict requirement — the agent uses all messages in the prompt, and the SYSTEM message is optional (if it is omitted, the framework still prepends the output-schema instruction automatically).

{{< tabs "Prompt" >}}

{{< tab "Python" >}}
```python
system_prompt_str = """
    Analyze 
    ...
    
    Example input format: 
    ...
    
    Ensure your response can be parsed by Python JSON, using this format as an example:
    ...
    """
    
# Prompt for review analysis react agent.
my_prompt = Prompt.from_messages(
    messages=[
        ChatMessage.system(system_prompt_str),
        # For react agent, if the input element is not primitive types,
        # framework will deserialize input element to dict and fill the prompt.
        # Note, the input element should be primitive types, BaseModel or Row.
        ChatMessage.user(
            """
            "id": {id},
            "review": {review}
            """
        ),
    ],
) 
```
{{< /tab >}}

{{< tab "Java" >}}
```java
String systemPromptString =
        "Analyze ..."
                + "Example input format:\n"
                + "..."
                + "Ensure your response can be parsed by Java JSON, using this format as an example:\n"
                + "...";
    
// Prompt for review analysis react agent.
Prompt myPrompt = Prompt.fromMessages(
        Arrays.asList(
                new ChatMessage(MessageRole.SYSTEM, systemPromptString),
                new ChatMessage(
                        MessageRole.USER,
                        "{\"id\": \"{id}\",\n" + "\"review\": \"{review}\"}")));
```
{{< /tab >}}

{{< /tabs >}}

The USER-message template uses `{placeholder}` syntax. The placeholder names are derived from the agent input element, and depend on its type:

- **Primitive** (`int`, `str`, `float`, `bool`, ...): a single `{input}` placeholder.
- **`Row`**: one placeholder per field name (`row.as_dict()` keys in Python, `row.getFieldNames()` in Java).
- **`dict` / `Map`**: the keys are used directly.
- **`BaseModel` (Python) / Pojo (Java)**: the object's field names.

For example, the prompt snippet above uses `{id}` and `{review}` because the input element's fields are named `id` and `review`. A placeholder whose name does not match a field is left unchanged in the text.

If the input element is primitive types, like `int`, `str` and so on, the second message should be

{{< tabs "Prepare Agents Execution Environment" >}}

{{< tab "Python" >}}
```python
ChatMessage.user("{input}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
new ChatMessage(MessageRole.USER, "{input}")
```
{{< /tab >}}

{{< /tabs >}}

See [Prompt]({{< ref "docs/development/prompts" >}}) for more details.

### Output Schema
User can set output schema to configure the ReAct Agent output type. If output schema is set, the ReAct Agent will deserialize the llm response to expected type. 

The output schema should be a `BaseModel` subclass (Python) / a Pojo class (Java), or a `RowTypeInfo` (both).

{{< tabs "Output Schema" >}}

{{< tab "Python" >}}
```python
class MyBaseModelDataType(BaseModel):
    id: str
    score: int
    reasons: list[str]

# Currently, for RowTypeInfo, only support BasicType fields.
my_row_type_info = RowTypeInfo(
        [BasicTypeInfo.STRING_TYPE_INFO(), BasicTypeInfo.INT_TYPE_INFO()],
        ["id", "score"],
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@JsonSerialize
@JsonDeserialize
public static class MyBaseModelDataType {
    private final String id;
    private final int score;
    private final List<String> reasons;

    @JsonCreator
    public MyBaseModelDataType(
            @JsonProperty("id") String id,
            @JsonProperty("score") int score,
            @JsonProperty("reasons") List<String> reasons) {
        this.id = id;
        this.score = score;
        this.reasons = reasons;
    }

    public MyBaseModelDataType() {
        id = null;
        score = 0;
        reasons = List.of();
    }

    public String getId() {
        return id;
    }

    public int getScore() {
        return score;
    }

    public List<String> getReasons() {
        return reasons;
    }

    @Override
    public String toString() {
        return String.format(
                "MyBaseModelDataType{id='%s', score=%d, reasons=%s}", id, score, reasons);
    }
}

// Currently, for RowTypeInfo, only support BasicType fields.
RowTypeInfo myRowTypeInfo =
        new RowTypeInfo(
                new TypeInformation[] {
                        BasicTypeInfo.STRING_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO
                },
                new String[] {"id", "score"});
```
{{< /tab >}}

{{< /tabs >}}

## As a General-Purpose Sub-Agent

A ReActAgent can also run as a sub-agent of a parent agent. Register it as an `AGENT` resource of the parent and it runs as an internal sub-agent — an isolated, durable agent inside the same job — that keeps its own event loop, resources and conversation. The parent reaches it through two paths: the parent model delegating to it as a tool, or a parent action submitting a prompt directly.

### Register the Child Agent

Register the child under a name, like any other resource. The parent can be any agent, including another ReActAgent.

{{< tabs "Register Sub-Agent Child" >}}

{{< tab "Python" >}}
```python
reviewer = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="reviewer_connection",
        model="qwen3:8b",
    ),
)
# The connection is registered on the child itself, keeping it self-contained.
reviewer.add_resource(
    "reviewer_connection",
    ResourceType.CHAT_MODEL_CONNECTION,
    ResourceDescriptor(clazz=ResourceName.ChatModel.OLLAMA_CONNECTION),
)

parent = ReActAgent(chat_model=parent_chat_model_descriptor)
parent.add_resource("reviewer", ResourceType.AGENT, reviewer)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent reviewer =
        new ReActAgent(
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "reviewerConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .build(),
                null,
                null);
// The connection is registered on the child itself, keeping it self-contained.
reviewer.addResource(
        "reviewerConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .build());

ReActAgent parent = new ReActAgent(parentChatModelDescriptor, null, null);
parent.addResource("reviewer", ResourceType.AGENT, reviewer);
```
{{< /tab >}}

{{< /tabs >}}

{{< hint warning >}}
Keep the child self-contained and register the chat model connection it uses on the child itself, as above. This is required in Python, where a child resolves resources against its own plan only — a connection registered solely on the parent is not visible to the child.
{{< /hint >}}

### Model-Driven Delegation

Declare the child's name under `subagents` of the parent's chat model, and the parent model can delegate to it: the child is presented as a `_subagent_<name>` tool whose description and input schema come from the child agent.

{{< tabs "Model-Driven Delegation" >}}

{{< tab "Python" >}}
```python
parent = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="my_ollama_connection",
        model="qwen3:8b",
        subagents=["reviewer"],
    ),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent parent =
        new ReActAgent(
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "myOllamaConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .addInitialArgument("subagents", List.of("reviewer"))
                        .build(),
                null,
                null);
```
{{< /tab >}}

{{< /tabs >}}

A ReActAgent child works with the defaults out of the box. The default input schema declares a single `input` string field, and the framework routes that field to the child as its user message — directly when the child declares no prompt, or through the child's `{input}` placeholder when it declares one. The child's answer flows back to the parent model as the tool observation, so the parent can continue reasoning over it.

### Caller-Driven Delegation

A parent action can submit a prompt to the child directly and await its outcome.

{{< tabs "Caller-Driven Delegation" >}}

{{< tab "Python" >}}
```python
class DelegatingAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def delegate(event: Event, ctx: RunnerContext) -> None:
        child = ctx.get_resource("reviewer", ResourceType.AGENT)
        future = await child.submit(ctx, "review the diff")
        result = await future
        if result.success:
            ctx.send_event(OutputEvent(output=result.result[0]))
        else:
            ctx.send_event(OutputEvent(output=f"failed: {result.error_message}"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class DelegatingAgent extends Agent {

    @Action(EventType.InputEvent)
    public static void delegate(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child =
                (SubagentSetup) ctx.getResource("reviewer", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "review the diff").await();
        if (result.isSuccess()) {
            String answer = String.valueOf(((List<?>) result.getResult()).get(0));
            ctx.sendEvent(new OutputEvent(answer));
        } else {
            ctx.sendEvent(new OutputEvent("failed: " + result.getErrorMessage()));
        }
    }
}
```
{{< /tab >}}

{{< /tabs >}}

The awaited value is a `SubagentResult`. On success, its `result` field (`getResult()` in Java) is the list of outputs the child emitted — a ReActAgent child emits a single final answer, so the first element is what you usually want. On failure, `success` is `False` / `isSuccess()` is `false`, and `error_message` / `getErrorMessage()` carries the reason.

### Metadata Presented to the Caller

By default, a ReActAgent presents itself as a general-purpose agent: the description is "A general-purpose agent that completes a delegated task using its tools.", and the input schema declares the single `input` field the delegation paths above rely on. Override either through the constructor when the child has a more specific role.

{{< tabs "Sub-Agent Metadata" >}}

{{< tab "Python" >}}
```python
reviewer = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=Prompt.from_text(
        "Review the following code diff and report any problems:\n\n{diff}"
    ),
    subagent_description="Reviews a code diff and reports problems.",
    subagent_input_schema=(
        '{"type":"object","properties":{"diff":{"type":"string"}},'
        '"required":["diff"]}'
    ),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent reviewer =
        new ReActAgent(
                chatModelDescriptor,
                Prompt.fromText(
                        "Review the following code diff and report any problems:\n\n{diff}"),
                null,
                "Reviews a code diff and reports problems.",
                "{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}},"
                        + "\"required\":[\"diff\"]}");
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
A custom schema with more fields pairs with a child prompt whose placeholders match the field names — the model's arguments fill the placeholders. Without a child prompt, keep the single `input` field: it is the one structured shape the prompt-free path passes through as the user message.
{{< /hint >}}

