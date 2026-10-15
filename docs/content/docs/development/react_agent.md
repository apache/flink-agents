---
title: "Built-in ReAct Agent"
weight: 6
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

Flink Agents provides `ReActAgent` as a built-in subclass of `Agent` that implements the ReAct pattern.

ReAct Agent is a general paradigm that combines reasoning and action capabilities to solve complex tasks. A `ReActAgent` requires a chat model. You can optionally provide a prompt, an output schema, tools, and skills; the model then decides how to achieve the goal and when to use the capabilities you expose.

{{< hint info >}}
The snippets on this page are fragments that each focus on a single concept. For an end-to-end runnable walkthrough, see the [Use the Built-in ReAct Agent]({{< ref "docs/get-started/quickstart/react_agent" >}}) and the full source: [`react_agent_example.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/react_agent_example.py) (Python) and [`ReActAgentExample.java`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/ReActAgentExample.java) (Java).
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
from flink_agents.api.skills import Skills
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
import org.apache.flink.agents.api.skills.Skills;
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

The chat model descriptor is the only required configuration. The example below also supplies an optional prompt and output schema. In Python, omitted optional arguments default to `None`. In Java, pass `null` for the prompt or output schema when you do not need it.

{{< tabs "ReAct Agent Example" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=my_prompt,
    output_schema=MyBaseModelDataType,  # or my_row_type_info
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
Use a `ResourceDescriptor` to select the required chat model setup and pass its arguments. See [Chat Models]({{< ref "docs/development/calling-llms/chat-models" >}}) for the provider-specific connection and model configuration.

Tools are optional. To expose them to the ReAct Agent, list their registered resource names in the descriptor's `tools` argument:

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

Register the referenced tools (this can also be done with a YAML file — see [Tool Use]({{< ref "docs/development/calling-llms/tool_use#register-tool-to-execution-environment" >}}) for the full guide):

{{< tabs "Register Tools" >}}

{{< tab "Python" >}}
```python
agents_env.add_resource(
    "my_tool1", ResourceType.TOOL, Tool.from_callable(my_tool1)
)
# Register my_tool2 the same way.
```
{{< /tab >}}

{{< tab "Java" >}}
```java
agentsEnv.addResource(
        "myTool1",
        ResourceType.TOOL,
        Tool.fromMethod(MyAgentExample.class.getMethod("myTool1", String.class)));
// Register myTool2 the same way.
```
{{< /tab >}}

{{< /tabs >}}

Registering a Vector Store resource does not make a ReAct Agent retrieve from it automatically. Implement retrieval explicitly, for example by exposing it as a tool.

### Prompt
The prompt is optional. Without one, the agent directly converts a Java `String` or primitive wrapper, a Python `str`, `bool`, `int`, `float`, or `None`, or a Java `Map`/Python `dict` whose only key is `input` into a user message. Other structured inputs require a prompt to map their fields into one or more user messages; otherwise, the agent rejects the input at runtime.

A typical prompt contains two messages: a SYSTEM message that tells the agent what to do (and gives input and output examples), and a USER message that describes how to convert the input element into a text string. This is a recommended pattern, not a strict requirement — the agent uses all messages in the prompt, and the SYSTEM message is optional. When an output schema is configured, the framework still prepends its schema instruction if the prompt has no SYSTEM message.

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
        # Structured input fields are matched to the prompt placeholders.
        # The input can be a primitive, dict, BaseModel, or Row.
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
- **`BaseModel` (Python) / POJO (Java)**: the object's field names.

For example, the prompt snippet above uses `{id}` and `{review}` because the input element's fields are named `id` and `review`. A placeholder whose name does not match a field is left unchanged in the text.

When a prompt formats a primitive input, use the `{input}` placeholder in its USER message:

{{< tabs "Primitive Input Placeholder" >}}

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

See [Prompt]({{< ref "docs/development/calling-llms/prompts" >}}) for more details.

### Output Schema
The output schema is optional. Without one, the agent does not add a schema instruction, parse or validate the final response against a schema, or perform type conversion. It emits the text of the final assistant message as a `String` (Java) or `str` (Python). This text is the concatenation of the message's top-level text blocks; media, reasoning, and tool-call blocks are not included.

When you set an output schema, the agent emits the parsed result instead. The schema should be a `BaseModel` subclass (Python), a POJO class (Java), or a `RowTypeInfo` (both). If the final response cannot be parsed after the configured retries, the chat request fails and the ReAct Agent propagates that failure.

When the chat model's provider supports it, the schema is enforced natively by the provider rather than only described in the prompt. See [Structured Output]({{< ref "docs/development/calling-llms/structured-output" >}}) for the strategies, result types, supported providers, schema rules, and failure behavior.

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

### Skills
Skills are optional. You can pass [skills]({{< ref "docs/development/calling-llms/skills" >}}) to the constructor instead of adding a `skills` argument to the chat model descriptor. Two forms are accepted: a list of skill names, or a `Skills` object declaring where the skills are loaded from.

**Skill names.** Pass the names of skills that are already declared to the agent:

{{< tabs "Skills" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=my_prompt,
    skills=["math-calculator"],
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent myReActAgent =
        new ReActAgent(chatModelDescriptor, myPrompt, null, List.of("math-calculator"));
```
{{< /tab >}}

{{< /tabs >}}

The constructor skills are combined with any `skills` already set on the chat model descriptor:

- The descriptor's skills come first, followed by the constructor skills; a name listed more than once is kept only at its first occurrence.
- Passing no skills, or an empty list, adds nothing: the descriptor is used exactly as given.
- The descriptor you pass in is not modified; the agent uses a copy with the combined skills.
- Only skill names are combined. The command policy of the built-in `bash` tool, such as `allowed_commands`, is always taken from the chat model descriptor, so set it there.
- The sources behind those names have to be declared somewhere the agent can see them: a `@Skills`/`@skills` method, the execution environment, or the agent itself.

**Skill sources.** Pass a `Skills` object to declare the sources and let the framework resolve the skill names at runtime:

{{< tabs "Skill Sources" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=my_prompt,
    skills=Skills.from_local_dir("./skills"),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent myReActAgent =
        new ReActAgent(
                chatModelDescriptor,
                myPrompt,
                null,
                Skills.fromLocalDir("./skills"));
```
{{< /tab >}}

{{< /tabs >}}

The constructor registers those sources on the agent, so they do not also need `add_resource(..., ResourceType.SKILLS, ...)` on the execution environment. Every skill found in the sources becomes available, and the resolved names are appended after any explicitly named ones. A single `Skills` object may declare several sources; to combine sources held in separate objects, gather them into one `Skills`, declare further `@Skills`/`@skills` methods, or register them on the agent with `addResource(..., ResourceType.SKILLS, ...)`.

**Which declaration wins.** Sources reach the agent from three levels. `@Skills`/`@skills` methods are merged first, then the execution environment's `SKILLS` resources, then the agent's own. A declaration from a later level wins when two of them resolve to the same skill name.

## As a Sub-Agent

A ReActAgent can run as a sub-agent of another agent, delegated to by the parent's chat model or called from the parent's actions. See [Use a ReActAgent as the child]({{< ref "docs/development/multi-agent/subagents#use-a-reactagent-as-the-child" >}}).
