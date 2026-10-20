---
title: "YAML API"
weight: 6
type: docs
aliases:
  - /docs/development/yaml/
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

Flink Agents provides a **declarative YAML API** for defining and orchestrating Agents.
It uses the same Events, Actions, and Resources as the Java and Python APIs, described in
[Core Concepts]({{< ref "docs/development/create-agent/overview#core-concepts" >}}).

Parts of an Agent definition can be expressed directly in YAML, while the
implementation logic remains in Java or Python code:

**What can be defined in YAML:**

- Which Actions make up an Agent and which Events trigger them.
- Which Resources are available and how components reference them.
- The existing implementations to use and their configuration.
- Inline prompt text or messages.

**What remains in code:**

- Action and tool logic.
- Custom Resource implementations.
- Custom Event classes.

This lets you change component configuration and wiring while reusing the same code.

If you'd rather declare agents directly in code, see [Agent]({{< ref "docs/development/create-agent/overview" >}}) and [Built-in ReAct Agent]({{< ref "docs/development/react_agent" >}}).

## Declaring an Agent

This section walks through:

1. Declaring a single agent in one YAML file.
2. Declaring multiple agents in one YAML file.
3. Declaring shared Resources and Actions that any agent in the file (or any later-loaded file) can reuse.
4. Declaring sub-agents and referencing them from a parent Agent.

### Declaring a single agent

The example below declares one agent named `review_analysis_agent` with a chat-model connection, a chat-model setup, a function tool, and two actions. It demonstrates every field you typically use.

```yaml
agents:
  - name: review_analysis_agent
    description: Analyze product reviews and emit a satisfaction score.

    # ---- Actions ----
    actions:
      - name: process_input
        function: my_pkg.actions:process_input
        trigger_conditions: [input]
        type: python
      - name: process_chat_response
        function: my_pkg.actions:process_chat_response
        trigger_conditions: [chat_response]
        type: python

    # ---- Resources ----
    chat_model_connections:
      - name: ollama_server
        clazz: ollama
        type: python
        base_url: http://localhost:11434

    chat_model_setups:
      - name: review_analysis_model
        clazz: ollama
        type: python
        connection: ollama_server
        model: qwen3:8b
        prompt: review_analysis_prompt
        tools: [notify_shipping_manager]
        extract_reasoning: true

    prompts:
      - name: review_analysis_prompt
        messages:
          - role: system
            content: "Analyze the review and return JSON with score + reasons."
          - role: user
            content: "{input}"

    tools:
      - name: notify_shipping_manager
        function: my_pkg.actions:notify_shipping_manager
        type: python
```

#### Agent properties

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Agent name. Must be unique across the environment. Used to apply the agent by name later. |
| `description` | Optional | Free-form description of what the agent does. Not surfaced at runtime. |

#### Resources

Declare Resources as lists under the agent, with a `name` unique within each section.
The section determines the resource type: for example, `chat_model_setups` registers
`CHAT_MODEL` Resources and `tools` registers `TOOL` Resources. For Resources configured
by implementation class and for Tools, the optional `type: python` or `type: java`
field selects the implementation language. Prompts and Skills do not accept `type`.

These declarations register Resources for use by name, just like decorators, annotations,
or `add_resource` / `addResource`. Actions can get their instances through
`ctx.get_resource` / `ctx.getResource`; see
[Referencing and Accessing Resources]({{< ref "docs/development/create-agent/resources#referencing-and-accessing-resources" >}}).

**Prompt** — declarative prompt template. Exactly one of `text` or `messages` must be non-empty.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Prompt name (referenced by chat-model setups). |
| `text` | Required if `messages` is unset or empty | Non-empty string prompt template. Corresponds to `Prompt.from_text` / `Prompt.fromText`. |
| `messages` | Required if `text` is unset or empty | Non-empty list of message templates. Corresponds to `Prompt.from_messages` / `Prompt.fromMessages`. Each entry has `role` (`system` / `user` / `assistant` / `tool`) and `content`. |

Omitted or `null` fields, `text: ""`, and `messages: []` are treated as unset.
If both alternatives are non-empty, or neither is non-empty, YAML loading fails
validation. Neither alternative takes precedence over the other.

```yaml
prompts:
  - name: prompt1
    messages:
      - {role: system, content: "..."}
      - {role: user,   content: "{input}"}
  - name: prompt2
    text: "this is the {value}"
```

`{variable_name}` is the only substitution syntax, and there is no `{{` / `}}` escape — write JSON examples in prompt `content` with single braces. See [Brace Handling]({{< ref "docs/development/calling-llms/prompts#brace-handling" >}}).

**Tool** — points at a callable that the chat model can invoke.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Tool name (referenced from `chat_model_setups[].tools`). |
| `function` | Required | Fully-qualified callable in the form `<module-or-class>:<qualname>`. See [Function references](#function-references). |
| `type` | Optional | Implementation language: `python` or `java`. Defaults to `python` (see [Selecting the implementation language](#selecting-the-implementation-language)). |
| `parameter_types` | Required for Java / Forbidden for Python | One Java type per declared parameter, in order. Use `[]` for a Java tool with no parameters. Python tool signatures are reflected from the callable. |
| `injected_args` | Optional | Tool arguments injected by the framework at execution time. Declare a map from tool parameter name to `{source, key}`. These arguments are hidden from the model-facing tool schema. |

```yaml
tools:
  - name: my_tool
    function: my_pkg.tools:my_tool
    type: python
```

For **Java tools**, `parameter_types` is required because Java methods can be overloaded — list one Java type per declared parameter, in order.

```yaml
tools:
  - name: add
    type: java
    function: com.example.MyTools:add
    parameter_types: [java.lang.Integer, java.lang.Integer]
```

{{< hint info >}}
Generic type arguments are not part of the JVM method descriptor and must not be
included (`java.util.List`, not `java.util.List<String>`). Boxed primitives
(`java.lang.Integer`, etc.) and bare primitives (`int`, `boolean`, ...) are both accepted.
{{< /hint >}}

`injected_args` maps tool parameter names to injection settings. Each entry must be
an object with the following optional fields. Omit `injected_args` or use `{}` if
you do not need to declare parameter injection in YAML.

| Field | Required | Description |
|-------|----------|-------------|
| `source` | Optional | One of `config`, `sensory_memory`, or `short_term_memory`. Defaults to `sensory_memory`. |
| `key` | Optional | Configuration key or memory path. If omitted, `null`, or empty, defaults to the tool parameter name. |

For example, declare injection for the `tenant_id` parameter of `query_order`:

```yaml
tools:
  - name: query_order
    function: my_pkg.tools:query_order
    injected_args:
      tenant_id:
        source: config
        key: tenant_id
```

See [Tool Parameter Injection]({{< ref "docs/development/calling-llms/tool_use#tool-parameter-injection" >}})
for when to use injection, runtime value resolution, Java annotations, and
conflict and failure rules.

**Skills** — bundles of agent skill assets loaded from one or more sources.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Skills resource name. |
| `paths` | Optional | `local` scheme: list of directories or `.zip` files. |
| `urls` | Optional | `url` scheme: list of HTTPS URLs pointing to `.zip` archives. |
| `url_sources` | Optional | `url` scheme: list of objects with a required `url`, optional `sha256` digest, and optional `allow_insecure_http` flag (defaults to `false`). Set the flag to `true` to allow plain HTTP. |
| `classpath` | Optional | `classpath` scheme: list of Java classpath resource paths. |
| `package` | Optional | `package` scheme: list of Python `{package, resource}` pairs. |

{{< hint info >}}
At least one of `paths`, `urls`, `url_sources`, `classpath`, or `package` must be non-empty.
Multiple sources can be configured together.
{{< /hint >}}

```yaml
skills:
  - name: agent_skills
    paths:
      - ./skills
    urls:
      - https://example.com/unpinned-skills.zip
    url_sources:
      - url: https://example.com/skills.zip
        sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    classpath:
      - skills/my-skills
    package:
      - package: my_pkg
        resource: skills/
```

Both `classpath` and `package` sources are supported by Java and Python agents.
See
[Skill Sources]({{< ref "docs/development/calling-llms/skills#skill-sources" >}})
for URL restrictions, archive integrity and security guidance, and dependency and
cross-language runtime requirements.

**Resources configured by implementation class** — `chat_model_connections`,
`chat_model_setups`, `decision_model_connections`, `decision_model_setups`,
`embedding_model_connections`, `embedding_model_setups`, `vector_stores`, and
`mcp_servers`, plus `subagents` for external sub-agents. Declare the implementation
and its configuration using the fields below.
This corresponds to a `ResourceDescriptor` in Java or Python; the framework creates the
Resource instance at runtime.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Resource name (referenced by other resources or actions). |
| `clazz` | Required | Either a built-in [alias](#class-aliases) (e.g. `ollama`, `openai`) or a fully-qualified class path. |
| `type` | Optional | Implementation language: `python` or `java`. Defaults to `python` (see [Selecting the implementation language](#selecting-the-implementation-language)). |
| *extras* | Depends on the selected implementation | Additional configuration for the selected implementation (e.g. `base_url`, `endpoint`, `model`, `request_timeout`). See its provider documentation for required fields and defaults. |

```yaml
chat_model_connections:
  - name: my_connection
    clazz: ollama
    type: python
    base_url: http://localhost:11434

mcp_servers:
  - name: my_mcp
    clazz: mcp
    type: python
    endpoint: http://127.0.0.1:8000/mcp
```

**Sub-agents** are registered under `subagents` as `AGENT` Resources. Internal
sub-agents reference another Agent in the same file or already loaded into the same
environment; external sub-agents name a setup implementation. See [Declaring sub-agents](#declaring-sub-agents).

**References between Resources.** Fields such as `connection`, `prompt`, and `tools`
refer to registered Resource names. The Agent's own registrations take precedence over
shared Resources from the execution environment. In the [single-agent example](#declaring-a-single-agent):

| Field | Refers to | Example |
|-------|-----------|---------|
| `chat_model_setups[].connection` | a `chat_model_connections[]` name | `connection: ollama_server` |
| `chat_model_setups[].prompt` | a `prompts[]` name | `prompt: review_analysis_prompt` |
| `chat_model_setups[].tools` | a list of `tools[]` names | `tools: [notify_shipping_manager]` |
| `chat_model_setups[].subagents` | a list of `subagents[]` resource names | `subagents: [reviewer]` |
| `decision_model_setups[].connection` | a `decision_model_connections[]` name | `connection: decision_connection` |
| `embedding_model_setups[].connection` | an `embedding_model_connections[]` name | `connection: my_embedding_server` |

The referenced Resource must be registered on the Agent or its execution environment.
The setup's `name: review_analysis_model` identifies the Resource in your application;
its `model: qwen3:8b` selects the model on the Ollama server.

An Action can send a `ChatRequestEvent` naming `review_analysis_model` to use the
[built-in chat Action]({{< ref "docs/development/calling-llms/chat#chat-events" >}}),
which gets and calls the model Resource. This event-based path is provided by the built-in
Action; registering a Resource alone does not create an Event handler for it.

#### Actions

`actions:` is a list. Each entry is either an inline action **map** or, when reusing a shared action, a bare **string** referring to the shared action's name.

[Built-in Actions]({{< ref "docs/development/create-agent/events#built-in-events-and-actions" >}})
are registered automatically; you do not need to list them under `actions:`.

Inline action (map) fields:

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Action name (unique within the agent). |
| `function` | Required | Fully-qualified callable in the form `<module-or-class>:<qualname>`. See [Function references](#function-references). |
| `trigger_conditions` | Required | Non-empty list of strings containing exact event types or condition expressions that return a Boolean. Each string must contain at least one non-whitespace character. Built-in [event aliases](#event-aliases) (`input`, `chat_request`, ...) may be used as complete entries. All entries use OR semantics. |
| `type` | Optional | Implementation language: `python` or `java`. Defaults to `python` (see [Selecting the implementation language](#selecting-the-implementation-language)). |
| `config` | Optional | Free-form configuration map passed to the action at runtime. |

For built-in event types, prefer an [event alias](#event-aliases) such as `input`. Trigger-condition
matching and expression semantics are shared with annotated and programmatically registered actions;
see [Trigger Conditions]({{< ref "docs/development/create-agent/actions#trigger-conditions" >}}).

Every entry must remain a non-empty string after YAML parsing. Quote condition expressions, for
example `"ready == true"`. Quotes are required for literal expressions such as `"true"`; without
them, YAML parses `true`, `false`, or `null` as a non-string value. When an exact custom event type
requires quotes, preserve those quotes inside the YAML string:

```yaml
trigger_conditions:
  - order-created
  - "'order:created'"
  - "'EventType.custom'"
```

An `actions` list can mix inline actions and shared-action references:

```yaml
actions:
  - name: action1
    function: my_pkg.actions:action1
    trigger_conditions: [input, custom_event]
    type: python
  - name: action2
    function: my_pkg.actions:action2
    trigger_conditions:
      - "type == EventType.ChatResponseEvent && size(response.message.blocks) > 0"
    type: python
  - action3                       # shared action reference (declared at file level)
```

Action method signatures are fixed (`(Event, RunnerContext)`), so there is no `parameter_types` field on actions.

### Declaring multiple agents in one file

A single YAML file can declare more than one agent under `agents:`. Each agent is independent and gets its own resource set; agent names must be unique within the file (and across the whole environment, once loaded).

```yaml
agents:
  - name: agent1
    description: The first agent
    # actions
    # resources

  - name: agent2
    description: The second agent
    # actions
    # resources
```

### Declaring and reusing shared Resources and Actions

Any resource section (`prompts`, `tools`, `chat_model_connections`, ...) or `actions:` declared as a **top-level sibling** of `agents:` is **shared**: it is registered on the `AgentsExecutionEnvironment` itself, and any agent in the file — or in any file later loaded into the same environment — can reference it by name.

**Shared resources** are referenced inside an agent simply by name:

```yaml
agents:
  - name: agent1
    description: The first agent
    chat_model_setups:
      - name: my_llm
        clazz: ollama
        connection: my_connection      # references the shared connection
        model: qwen3:8b
        think: false

  - name: agent2
    description: The second agent
    chat_model_setups:
      - name: my_llm
        clazz: ollama
        connection: my_connection      # same shared connection reused
        model: qwen3:8b
        think: true

# shared resource at the file level
chat_model_connections:
  - name: my_connection
    clazz: ollama
    base_url: http://localhost:11434
```

**Shared actions** are referenced inside an agent's `actions:` list by writing the action's name as a bare string:

```yaml
agents:
  - name: agent1
    description: The first agent
    actions:
      - action1                        # shared action
      - name: my_action
        function: my_pkg.actions:my_action
        trigger_conditions: [input]

  - name: agent2
    description: The second agent
    actions:
      - action1                        # same shared action reused

# shared actions at the file level
actions:
  - name: action1
    function: my_pkg.actions:action1
    trigger_conditions: [input]
    type: python
  - name: action2
    function: my_pkg.actions:action2
    trigger_conditions: [chat_response]
    type: python
```

{{< hint info >}}
Composing one agent across multiple YAML files is **not** supported. If two files loaded into the same environment declare the same agent name, or the same shared resource/action name, the second `load_yaml` call raises an error.
{{< /hint >}}

### Declaring sub-agents

Use an internal sub-agent to delegate to another Agent defined in your application,
or an external sub-agent to call an agent hosted by another system.

#### Internal sub-agents

To make a child Agent available to a parent Agent, add an entry to the parent's
`subagents` list. Each entry uses the following fields:

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Required | Resource name the parent uses to call this sub-agent. |
| `agent` | Required | Value of the child Agent's `name` field in the top-level `agents` list. |
| `description` | Optional | Describes when the model should delegate to this child. Defaults to an empty string; a generic description is used if none is supplied. |
| `input_schema` | Required for model-selected calls | JSON Schema written as a YAML mapping describing the child's input arguments. It can be omitted when calling the sub-agent directly from an Action. |

The following fragment extends the [single-agent example](#declaring-a-single-agent).
Keep the parent's existing configuration and add the declarations shown below.
The child Agent's Actions and Resources are omitted.

```yaml
agents:
  - name: review_analysis_agent
    # Existing Actions and Resources omitted.
    subagents:
      - name: reviewer
        agent: review_workflow
        description: Analyze product review details
        input_schema:
          type: object
          properties:
            review: {type: string}
          required: [review]
    chat_model_setups:
      - name: review_analysis_model
        # Keep the existing model configuration.
        subagents: [reviewer]

  - name: review_workflow
    # Define the child's Actions and Resources here.
```

Here, `agent: review_workflow` refers to the child defined with
`name: review_workflow`. The parent model uses the Resource name `reviewer` to call it.

Multiple parents may reference the same child definition. Declare internal
references under each parent's `subagents`; file-level `subagents` supports only
external setup declarations.

The referenced child Agent must be defined in the same YAML file or already loaded
into the same execution environment. Within one file, the child may appear before
or after its parent. For Agents split across files, load the child file before the
parent file. Files in a list are loaded in order, and separate loading calls use
the same rule:

{{< tabs "Load Sub-agent Files" >}}
{{< tab "Python" >}}
```python
agents_env.load_yaml(["child.yaml", "parent.yaml"])
```
{{< /tab >}}
{{< tab "Java" >}}
```java
agentsEnv.loadYaml(List.of(Paths.get("child.yaml"), Paths.get("parent.yaml")));
```
{{< /tab >}}
{{< /tabs >}}

An unknown child name fails loading of the referencing file; the loader does not
wait for later files. Agent names must remain unique across the environment.
Self-references and indirect cycles fail when the Agent plan is built. Register the
child's required Resources on the child itself, as described in
[Declare an internal sub-agent]({{< ref "docs/development/multi-agent/subagents#declare-an-internal-sub-agent" >}}).

For input/output behavior, model delegation, and execution requirements, see
[Subagents]({{< ref "docs/development/multi-agent/subagents" >}}).

#### External sub-agents

Use `clazz` to name a custom sub-agent setup implementation. Its additional fields
are passed to that implementation as configuration:

```yaml
subagents:
  - name: remote_reviewer
    clazz: my_app.agents.RemoteReviewSetup
    type: python
    endpoint: https://example.com/review
    description: Review code using the remote service
    input_schema: '{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}'
```

This example assumes `RemoteReviewSetup` accepts `endpoint`, `description`, and a
JSON string `input_schema`. Use `type: java` and a Java class name when loading an
external Java setup from a Java job. YAML external sub-agent declarations currently
require the setup language to match the loader; an omitted `type` defaults to Python.
Reference `remote_reviewer` in the chat model's `subagents` list to enable model delegation.
See [Declare an external sub-agent]({{< ref "docs/development/multi-agent/subagents#declare-an-external-sub-agent" >}})
for the implementation contract.

## Loading and Running

YAML describes an Agent but is not submitted to Flink by itself. Create a Java Flink
or PyFlink job that loads the YAML and applies the Agent, then submit that job. Install
the Java and Python dependencies required by the implementations referenced in the
YAML. See [Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages#integrate-the-agent-into-a-flink-job" >}}).

`AgentsExecutionEnvironment` exposes a `load_yaml` / `loadYaml` method that parses one or more YAML files and:

- registers all shared resources on the environment, and
- registers all declared agents on the environment.

Once loaded, an agent declared in YAML is applied **by name** through the same `AgentBuilder` as a code-defined agent — `.apply(...)` accepts either an `Agent` instance or the name of an agent previously registered on the environment.

{{< tabs "Load and Apply" >}}

{{< tab "Python" >}}
```python
agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
agents_env.load_yaml("path/to/agents.yaml")

review_analysis_res_stream = (
    agents_env.from_datastream(
        input=product_review_stream, key_selector=lambda x: x.id
    )
    .apply("review_analysis_agent")     # look up the agent by name
    .to_datastream()
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);
agentsEnv.loadYaml(Paths.get("path/to/agents.yaml"));

DataStream<Object> outputStream =
        agentsEnv
                .fromDataStream(inputStream, (KeySelector<MyPojo, String>) MyPojo::getId)
                .apply("review_analysis_agent")    // look up the agent by name
                .toDataStream();
```
{{< /tab >}}

{{< /tabs >}}

`load_yaml` / `loadYaml` accepts either a single path or a list of paths, and **can be called multiple times**. Multiple calls accumulate on the same environment; the same uniqueness rules apply across the combined state. Duplicate agent or resource names — within a file or across files — raise an error.

{{< tabs "Multi-File Loading" >}}

{{< tab "Python" >}}
```python
# Load multiple files in one call
agents_env.load_yaml(["./agents.yaml", "./shared.yaml"])

# Or call repeatedly — same result
agents_env.load_yaml("./agents.yaml")
agents_env.load_yaml("./shared.yaml")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Load multiple files in one call
agentsEnv.loadYaml(Paths.get("./agents.yaml"), Paths.get("./shared.yaml"));

// Or call repeatedly — same result
agentsEnv.loadYaml(Paths.get("./agents.yaml"));
agentsEnv.loadYaml(Paths.get("./shared.yaml"));
```
{{< /tab >}}

{{< /tabs >}}

A common pattern is to split a topology file (the agents themselves) from an infrastructure file (chat-model connections, vector stores, ...). The infrastructure file can be swapped per environment (dev / staging / prod) without touching the agent definitions. The `agents:` block is optional, so an infrastructure-only file (no `agents:` block) is loaded as shared resources.

For an end-to-end runnable walkthrough that loads a YAML-declared agent and runs it on Flink, see [Define an Agent with YAML]({{< ref "docs/get-started/quickstart/yaml_agent" >}}).

## Advanced Topics

### Function references

Actions and function tools point at user code through a single `function:` string in the form:

```
<module-or-class>:<qualname>
```

The colon separates the **left side** — a Python module or a Java class FQN — from the **right side** — the attribute path inside it.

{{< tabs "Function Reference Examples" >}}

{{< tab "Python" >}}
```yaml
# Top-level function in a module
function: my_pkg.actions:process_input

# Static method on a class (nested via dots in the qualname)
function: my_pkg.agents:ReviewAnalysisAgent.process_input
```
{{< /tab >}}

{{< tab "Java" >}}
```yaml
# Static method on a class
function: com.example.MyActions:processInput

# Static method on a nested/inner class
function: com.example.Outer$Inner:processInput
```
{{< /tab >}}

{{< /tabs >}}

### Selecting the implementation language

Resources configured by implementation class, Tools, and Actions accept an optional
`type:` field with values `python` or `java`. When omitted, the default is **`python`**
in both loaders. Prompts, Skills, and internal sub-agent references do not accept this field.

For components that accept `type`, set `type: java` explicitly when selecting a Java
implementation, even when loading the YAML from a Java job.

When `type:` resolves to the **opposite** language of the loader, the loader builds a cross-language descriptor that delegates to the other-language implementation — see [Cross-language agents](#cross-language-agents).

### Provider aliases

For `clazz:` on resource descriptors and for complete event-type entries in `trigger_conditions:`, you can use a short alias instead of a fully-qualified class path. Event alias replacement is an exact complete-entry lookup: `input` is replaced, while `type == input`, `attributes.kind == 'input'`, and the quoted event type `'input'` remain unchanged.

#### Event aliases

| Alias                          | Event type                       |
| ------------------------------ | -------------------------------- |
| `input`                        | `InputEvent`                     |
| `output`                       | `OutputEvent`                    |
| `chat_request`                 | `ChatRequestEvent`               |
| `chat_response`                | `ChatResponseEvent`              |
| `decision_request`             | `DecisionRequestEvent`           |
| `decision_response`            | `DecisionResponseEvent`          |
| `tool_request`                 | `ToolRequestEvent`               |
| `tool_response`                | `ToolResponseEvent`              |
| `context_retrieval_request`    | `ContextRetrievalRequestEvent`   |
| `context_retrieval_response`   | `ContextRetrievalResponseEvent`  |

{{< hint warning >}}
`output` resolves to `OutputEvent`, but output events are emitted directly downstream and bypass
action matching. Therefore, using `output` as a trigger condition never invokes an action. See
[Sending events]({{< ref "docs/development/create-agent/actions#sending-events" >}}).
{{< /hint >}}

For custom event types defined in your code, write the event's full `EVENT_TYPE` string instead of an alias.

#### Class aliases

Class aliases cover built-in providers. For custom providers, set `clazz:` to the
fully-qualified class name in Java or class path in Python; custom alias registration
is not supported.

Aliases for `clazz:` are keyed on resource type **and** implementation language. The same alias (e.g. `ollama`) resolves to a different class for `chat_model_connections` vs `chat_model_setups`, and for `python` vs `java`.

Common chat-model aliases:

| Alias                | `type: python`              | `type: java`                |
| -------------------- | --------------------------- | --------------------------- |
| `ollama`             | Ollama (Python)             | Ollama (Java)               |
| `openai`             | OpenAI Completions (Python) | —                           |
| `openai_completions` | —                           | OpenAI Completions (Java)   |
| `openai_responses`   | —                           | OpenAI Responses (Java)     |
| `anthropic`          | Anthropic                   | Anthropic                   |
| `gemini`             | —                           | Gemini (Java)               |
| `azure_openai`       | Azure OpenAI (Python)       | Azure OpenAI (Java)         |
| `bedrock`            | —                           | Bedrock (Java)              |
| `dashscope`          | DashScope (Alibaba Cloud Model Studio, Python) | —                           |
| `vllm`               | vLLM (Python)               | vLLM (Java)                 |
| `watsonx`            | IBM watsonx.ai (Python)     | IBM watsonx.ai (Java)       |

Decision-model aliases (apply to both `decision_model_connections` and `decision_model_setups`):

| Alias | `type: python` | `type: java` |
| ----- | -------------- | ------------ |
| `typesafe` | TypeSafe Jev | — |
| `openai` | OpenAI Decisions | — |

Decision providers currently have Python implementations. Both YAML loaders can
configure them with `type: python`. See
[Decision Models]({{< ref "docs/development/decision_models" >}}) for request types and Action examples.

Embedding-model aliases (apply to both `embedding_model_connections` and `embedding_model_setups`):

| Alias     | `type: python` | `type: java`   |
| --------- | -------------- | -------------- |
| `ollama`  | Ollama         | Ollama         |
| `openai`  | OpenAI         | OpenAI (Java)  |
| `dashscope` | DashScope (Alibaba Cloud Model Studio) | —              |
| `bedrock` | —              | Bedrock (Java) |

Vector-store aliases:

| Alias           | `type: python` | `type: java`    |
| --------------- | -------------- | --------------- |
| `chroma`        | Chroma         | —               |
| `mem0`          | Mem0           | —               |
| `elasticsearch` | —              | Elasticsearch   |
| `opensearch`    | —              | OpenSearch      |
| `s3_vectors`    | —              | S3 Vectors      |
| `milvus`        | —              | Milvus          |

The full alias tables live in `flink_agents.api.yaml.aliases` (Python) and `org.apache.flink.agents.api.yaml.Aliases` (Java).

### Cross-language agents

Setting `type:` to the **opposite** language of the loader bridges Python and Java pieces in the same agent. The loader resolves the alias against the cross-language bucket and wraps the resource in the appropriate cross-language proxy.

Example — a Python-side agent whose chat model uses a **Java** Ollama setup that also calls a **Java** function tool:

```yaml
agents:
  - name: cross_language_agent
    actions:
      - name: process_input
        function: my_pkg.actions:process_input
        trigger_conditions: [input]
      - name: process_chat_response
        function: my_pkg.actions:process_chat_response
        trigger_conditions: [chat_response]

    chat_model_connections:
      # Python Ollama connection used by the math chat model
      - name: ollama_connection
        clazz: ollama
        request_timeout: 240.0
      # Java Ollama connection used by the creative chat model
      - name: ollama_connection_java
        clazz: ollama
        type: java
        endpoint: http://localhost:11434
        requestTimeout: 240

    chat_model_setups:
      - name: math_chat_model
        clazz: ollama
        connection: ollama_connection
        model: qwen3:1.7b
        tools: [calculate_bmi]               # python -> java tool via the bridge

      - name: creative_chat_model
        clazz: ollama
        type: java
        connection: ollama_connection_java
        model: qwen3:1.7b

    tools:
      - name: calculate_bmi
        type: java
        function: com.example.HealthTools:calculateBMI
        parameter_types: [java.lang.Double, java.lang.Double]
```

Loaded with `agents_env.load_yaml(...)` on the Python side, this produces an agent where:

- The Python `process_input` and `process_chat_response` actions are Python functions.
- `math_chat_model` is a Python Ollama setup that calls a Java function tool through the cross-language tool bridge.
- `creative_chat_model` is a Java Ollama setup driven from the Python loader via the Java chat-model wrapper.

The **Java loader** also supports `type: python` for these components. Load the YAML
in a Java Flink job and use each component's `type:` to select its implementation.
Application payloads must use the supported
[Java and Python data types]({{< ref "docs/development/create-agent/choose-apis-and-languages#data-passed-between-java-and-python" >}}).

Not every resource type is cross-language. Currently `actions`, `tools`, `chat_model_connections`, `chat_model_setups`, `embedding_model_connections`, `embedding_model_setups`, and `vector_stores` support `type:` on the opposite language; others (e.g. `mcp_servers` and external `subagents`) do not.

## YAML API Specification

To help users and coding agents understand the YAML format and validate YAML files, Flink Agents publishes a language-neutral **JSON Schema** for the YAML document. The schema is checked in at {{< gh_link file="docs/yaml-schema.json" name="`docs/yaml-schema.json`" >}} — point your IDE's YAML language server at it for inline validation and autocompletion.

The schema benefits both humans and machines:

- It defines an interface contract, so LLMs and external systems can interact with Flink Agents declaratively.
- It is language-neutral, so it doubles as the compatibility contract between the Python and the Java loader.

Schema validation checks the document structure. It does
not verify that referenced classes/functions are installed, that a component supports
the selected implementation language, or that its provider is reachable. Before
running the job, follow [Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages" >}})
to check language support and provision the required dependencies. Java Tools also
require the documented `parameter_types`.
