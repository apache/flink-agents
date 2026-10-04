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

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
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
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
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


## Use ReActAgent as a subagent

`ReActAgent.for_subagent` (Python) and `ReActAgent.forSubagent` (Java) create a child agent that accepts an object containing one string field, `prompt`. Register it as an `AGENT` resource on the parent. The description tells the parent model when to delegate; instructions are literal system-message text for the child. Select the child's model, tools, and skills through its chat-model descriptor.

{{< tabs "ReAct Subagent Registration" >}}
{{< tab "Python" >}}
```python
child = ReActAgent.for_subagent(
    chat_model=chat_model_descriptor,
    description="Research a task and return an evidence-based answer.",
    instructions="Use the available tools and explain your findings concisely.",
)
parent.add_resource("researcher", ResourceType.AGENT, child)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
ReActAgent child = ReActAgent.forSubagent(
        chatModelDescriptor,
        "Research a task and return an evidence-based answer.",
        "Use the available tools and explain your findings concisely.",
        null);
parent.addResource("researcher", ResourceType.AGENT, child);
```
{{< /tab >}}
{{< /tabs >}}

An action can resolve and invoke the child explicitly. Python calling actions must be `async`; Java internal-subagent calls require a runtime with JDK 21 or later and continuation support. On JDK 21, include `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` in the JVM options (for example, Flink’s `env.java.opts.all`).

{{< tabs "ReAct Subagent Invocation" >}}
{{< tab "Python" >}}
```python
child = ctx.get_resource("researcher", ResourceType.AGENT)
result = await (await child.submit(ctx, {"prompt": "Investigate this incident."}))
if result.success:
    answer = result.result[0]
else:
    failure_reason = result.error_message
```
{{< /tab >}}
{{< tab "Java" >}}
```java
SubagentSetup child = (SubagentSetup) ctx.getResource("researcher", ResourceType.AGENT);
SubagentResult result = child.submit(
        ctx, Map.of("prompt", "Investigate this incident.")).await();
if (result.isSuccess()) {
    Object answer = ((List<?>) result.getResult()).get(0);
} else {
    String failureReason = result.getErrorMessage();
}
```
{{< /tab >}}
{{< /tabs >}}

The Java invocation uses `SubagentSetup` and `SubagentResult` from `org.apache.flink.agents.api.subagent`, plus `java.util.Map` and `java.util.List`. Internal agents return a list of emitted outputs; an ordinary ReActAgent emits one answer. The factory also accepts an optional JSON-model output schema: a Python `BaseModel` subclass or a Java POJO class. Row-based output schemas remain available through the ordinary ReActAgent constructor.

For model-driven delegation, add `subagents=["researcher"]` to the parent's Python chat-model descriptor, or `.addInitialArgument("subagents", List.of("researcher"))` to its Java descriptor builder. The framework advertises a callable named `_subagent_researcher` with the child's description and input schema. The child runs its own reasoning/tool loop, and its result becomes a tool response for the parent model. A failed child produces an error response for the parent to handle.

Waiting for a child releases the mailbox without occupying an async worker, so nested calls and model/tool execution can make progress even with `num-async-threads=1`.

Each invocation has its own conversation memory, shared across that child's actions and isolated from the parent and other invocations. Child resources take precedence over shared root resources, allowing a shared model connection alongside child-specific tools. A child without an output schema does not inherit the parent’s schema prompt. Registration is explicit; constructing a ReActAgent does not automatically add a general-purpose child.

Checkpoints retain internal call state and memory committed by completed child actions, including nested calls. On recovery, unfinished actions restart and reconnect to those calls. Their uncommitted memory writes and terminal outputs are recreated by execution. Replay of recorded model/tool results requires a configured action-state store; external side effects outside durable calls can repeat when an action restarts.
