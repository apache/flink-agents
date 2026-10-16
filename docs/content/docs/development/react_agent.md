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

## Create a ReAct Agent

The only required argument of a ReAct Agent is a chat model descriptor, which references a registered chat model connection by name. See [Chat Models]({{< ref "docs/development/calling-llms/chat-models" >}}) for the provider-specific connection and model configuration.

The following example registers a chat model connection on the `AgentsExecutionEnvironment`, defines the chat model descriptor, and creates the agent:

{{< tabs "Create a ReAct Agent" >}}

{{< tab "Python" >}}
```python
# Register a chat model connection under a resource name.
agents_env.add_resource(
    "my_ollama_connection",
    ResourceType.CHAT_MODEL_CONNECTION,
    ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=120
    ),
)

# Define the chat model descriptor.
chat_model_descriptor = ResourceDescriptor(
    clazz=ResourceName.ChatModel.OLLAMA_SETUP,
    connection="my_ollama_connection",
    model="qwen3:8b",
)

# Create the ReAct Agent.
my_react_agent = ReActAgent(chat_model=chat_model_descriptor)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Register a chat model connection under a resource name.
agentsEnv.addResource(
        "myOllamaConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 120)
                .build());

// Define the chat model descriptor.
ResourceDescriptor chatModelDescriptor =
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "myOllamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();

// Create the ReAct Agent; pass null for the optional prompt and output schema.
ReActAgent myReActAgent = new ReActAgent(chatModelDescriptor, null, null);
```
{{< /tab >}}

{{< /tabs >}}

You can optionally expose tools to the ReAct Agent. Register each tool on the `AgentsExecutionEnvironment` under a resource name (this can also be done with a YAML file — see [Tool Use]({{< ref "docs/development/calling-llms/tool_use#register-tool-to-execution-environment" >}}) for the full guide), then list the registered names in the chat model descriptor's `tools` argument. Each name must match a registered tool; otherwise the agent fails at runtime with a "resource not found" error.

{{< tabs "Expose Tools" >}}

{{< tab "Python" >}}
```python
# Register each tool under a resource name.
agents_env.add_resource(
    "my_tool1", ResourceType.TOOL, Tool.from_callable(my_tool1)
)
# Register my_tool2 the same way.

# List the registered tool names in tools.
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
// Register each tool under a resource name.
agentsEnv.addResource(
        "myTool1",
        ResourceType.TOOL,
        Tool.fromMethod(MyAgentExample.class.getMethod("myTool1", String.class)));
// Register myTool2 the same way.

// List the registered tool names in tools.
ResourceDescriptor chatModelDescriptor =
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "myOllamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("tools", List.of("myTool1", "myTool2"))
                .build();
```
{{< /tab >}}

{{< /tabs >}}

Registering a Vector Store resource does not make a ReAct Agent retrieve from it automatically. Implement retrieval explicitly, for example by exposing it as a tool.

### Optional Arguments

#### Prompt
The prompt is optional. Without one, the agent directly converts a Java `String` or primitive wrapper, a Python `str`, `bool`, `int`, `float`, or `None`, or a Java `Map`/Python `dict` whose only key is `input` into a user message. Other structured inputs require a prompt to map their fields into one or more user messages; otherwise, the agent rejects the input at runtime.

A typical prompt contains two messages: a SYSTEM message that tells the agent what to do (and gives input and output examples), and a USER message that describes how to convert the input element into a text string. This is a recommended pattern, not a strict requirement — the agent uses all messages in the prompt, and the SYSTEM message is optional. When an output schema is configured, the ReAct Agent adds its own schema instruction as a SYSTEM message, right after the prompt's first SYSTEM message or at the start if there is none.

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
        # Structured input fields (dict, BaseModel, or Row) fill the matching
        # placeholders. A primitive input only fills {input}.
        ChatMessage.user(
            """
            "id": {id},
            "review": {review}
            """
        ),
    ],
) 

my_react_agent = ReActAgent(chat_model=chat_model_descriptor, prompt=my_prompt)
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

ReActAgent myReActAgent = new ReActAgent(chatModelDescriptor, myPrompt, null);
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

#### Output Schema
The output schema is optional. If omitted, the agent returns the text of the final assistant response as a `String` (Java) or `str` (Python).

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

my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    output_schema=MyBaseModelDataType,  # or my_row_type_info
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

ReActAgent myReActAgent =
        new ReActAgent(
                chatModelDescriptor,
                null,
                MyBaseModelDataType.class // or myRowTypeInfo
        );
```
{{< /tab >}}

{{< /tabs >}}

#### Skills
Skills are optional. You can pass [skills]({{< ref "docs/development/calling-llms/skills" >}}) to the constructor in addition to, or instead of, a `skills` argument on the chat model descriptor; the agent uses both. Two forms are accepted: a list of skill names, or a `Skills` object declaring where the skills are loaded from.

**Skill names.** First register the skill sources on the `AgentsExecutionEnvironment`. The resource name identifies the collection of sources:

{{< tabs "Register Skills" >}}

{{< tab "Python" >}}
```python
agents_env.add_resource(
    "my_skills", ResourceType.SKILLS, Skills.from_local_dir("./skills")
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
agentsEnv.addResource(
        "mySkills", ResourceType.SKILLS, Skills.fromLocalDir("./skills"));
```
{{< /tab >}}

{{< /tabs >}}

Then pass the names declared in the sources' `SKILL.md` files to the constructor:

{{< tabs "Skills" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    skills=["math-calculator"],
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent myReActAgent =
        new ReActAgent(chatModelDescriptor, null, null, List.of("math-calculator"));
```
{{< /tab >}}

{{< /tabs >}}

Skills passed to the constructor are combined with those configured on the chat model descriptor, with duplicate names included only once. Referenced skills must be declared on the agent or its execution environment.

Configure `allowed_commands` on the chat model descriptor to control which commands the built-in `bash` tool may execute.

**Skill sources.** Alternatively, pass a `Skills` object directly to the constructor. The constructor registers the sources on the agent, so no separate registration on the execution environment is required:

{{< tabs "Skill Sources" >}}

{{< tab "Python" >}}
```python
my_react_agent = ReActAgent(
    chat_model=chat_model_descriptor,
    skills=Skills.from_local_dir("./skills"),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent myReActAgent =
        new ReActAgent(
                chatModelDescriptor,
                null,
                null,
                Skills.fromLocalDir("./skills"));
```
{{< /tab >}}

{{< /tabs >}}

Every skill found in the sources becomes available, and the resolved names are appended after any explicitly named ones. A single `Skills` object may declare several sources; to combine sources held in separate objects, gather them into one `Skills`, declare further `@Skills`/`@skills` methods, or register them on the agent with `addResource(..., ResourceType.SKILLS, ...)`.

**Which declaration wins.** Sources reach the agent from three levels. `@Skills`/`@skills` methods are merged first, then the execution environment's `SKILLS` resources, then the agent's own. A declaration from a later level wins when two of them resolve to the same skill name.

## As a Sub-Agent

A ReActAgent can run as a sub-agent of another agent, delegated to by the parent's chat model or called from the parent's actions. See [Use a ReActAgent as the child]({{< ref "docs/development/multi-agent/subagents#use-a-reactagent-as-the-child" >}}).
