---
title: YAML API
weight: 3
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

Beyond the Python and Java programmatic APIs, Flink Agents offers a **declarative YAML API** for describing agents. Compared with the programmatic APIs, the YAML API has the following advantages:

- **Human-friendly**: low entry barrier and easy to templatize across deployments.
- **Coding-agent-friendly**: a fixed schema reduces token cost, enables strict schema validation, and decouples configuration changes (declared parameters) from logic changes (action code).

A Flink Agents application is composed of three concepts; the YAML API exposes all three as declarative sections:

- **Events**: the messages flowing inside an agent. Every event has a type used for routing.
- **Actions**: code snippets triggered by events and emitting new events. They carry the agent's business logic.
- **Resources**: reusable components — Chat Models, Tools, Prompts, Vector Stores, MCP servers, etc. — referenced by actions at runtime.

A YAML file describes **Resources** and **Actions** declaratively; the **Event** types are referenced by name (built-in event aliases like `input` / `chat_response`, or your own event-type strings). The implementation classes (action functions, tool functions, custom types) still live in your Python or Java code; the YAML file only describes *how* those pieces are wired together.

If you'd rather declare agents directly in code, see [Workflow Agent]({{< ref "docs/development/workflow_agent" >}}) and [ReAct Agent]({{< ref "docs/development/react_agent" >}}).

## Declaring an Agent

This section walks through:

1. Declaring a single agent in one YAML file.
2. Declaring multiple agents in one YAML file.
3. Declaring shared Resources and Actions that any agent in the file (or any later-loaded file) can reuse.

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
        listen_to: [input]
        type: python
      - name: process_chat_response
        function: my_pkg.actions:process_chat_response
        listen_to: [chat_response]
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
| `name` | yes | Agent name. Must be unique across the environment. Used to apply the agent by name later. |
| `description` | no | Free-form description of what the agent does. Not surfaced at runtime. |

#### Resources

All resource sections are declared as lists under the agent. Each entry has a `name` unique within its section.

**Prompt** — declarative prompt template; pick exactly one of `text` or `messages`.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Prompt name (referenced by chat-model setups). |
| `text` | one-of | Single-string prompt template. Corresponds to `Prompt.from_text` / `Prompt.fromText`. |
| `messages` | one-of | Multi-turn message template. Corresponds to `Prompt.from_messages` / `Prompt.fromMessages`. Each entry has `role` (`system` / `user` / `assistant` / `tool`) and `content`. |

```yaml
prompts:
  - name: prompt1
    messages:
      - {role: system, content: "..."}
      - {role: user,   content: "{input}"}
  - name: prompt2
    text: "this is the {value}"
```

`{variable_name}` is the only substitution syntax, and there is no `{{` / `}}` escape — write JSON examples in prompt `content` with single braces. See [Brace Handling]({{< ref "docs/development/prompts#brace-handling" >}}).

**Tool** — points at a callable that the chat model can invoke.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Tool name (referenced from `chat_model_setups[].tools`). |
| `function` | yes | Fully-qualified callable in the form `<module-or-class>:<qualname>`. See [Function references](#function-references). |
| `type` | no | Implementation language: `python` or `java`. Defaults to `python` (see [Selecting the implementation language](#selecting-the-implementation-language)). |
| `parameter_types` | java only | Required for Java tools — one Java type FQN per declared parameter, in order. Forbidden for Python tools (the signature is reflected from the callable). |

```yaml
tools:
  - name: my_tool
    function: my_pkg.tools:my_tool
    type: python
```

For **Java tools**, `parameter_types` is required because Java methods can be overloaded — list one FQN per declared parameter, in order. Generic type arguments are not part of the JVM method descriptor and must not be included (`java.util.List`, not `java.util.List<String>`). Boxed primitives (`java.lang.Integer`, etc.) and bare primitives (`int`, `boolean`, ...) are both accepted.

```yaml
tools:
  - name: add
    type: java
    function: com.example.MyTools:add
    parameter_types: [java.lang.Integer, java.lang.Integer]
```

**Skills** — bundles of agent skill assets loaded from one or more sources. At least one of `paths` / `urls` / `classpath` / `package` must be non-empty; multiple sources can coexist.

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Skills resource name. |
| `paths` | one-of | `local` scheme: list of directories or `.zip` files. |
| `urls` | one-of | `url` scheme: list of `http(s)` URLs pointing to `.zip` archives. |
| `classpath` | one-of | `classpath` scheme (Java runtime only): list of classpath resource paths. |
| `package` | one-of | `package` scheme (Python runtime only): list of `{package, resource}` pairs. |

```yaml
skills:
  - name: agent_skills
    paths:
      - ./skills
    urls:
      - https://example.com/skills.zip
    classpath:
      - skills/my-skills
    package:
      - package: my_pkg
        resource: skills/
```

`classpath` is accepted by the Python parser for schema parity with Java but rejected at runtime, because the Python runtime does not register a `classpath` handler.

**ResourceDescriptor-backed resources** — `chat_model_connections`, `chat_model_setups`, `embedding_model_connections`, `embedding_model_setups`, `vector_stores`, `mcp_servers`. Unlike the resources above (which are constructed inline by the loader), these are described by a `ResourceDescriptor` and instantiated by the framework at runtime. They all share the same shape:

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Resource name (referenced by other resources or actions). |
| `clazz` | yes | Either a built-in [alias](#class-aliases) (e.g. `ollama`, `openai`) or a fully-qualified class path. |
| `type` | no | Implementation language: `python` or `java`. Defaults to `python` (see [Selecting the implementation language](#selecting-the-implementation-language)). |
| *extras* | no | Any additional keys are forwarded verbatim as `ResourceDescriptor` init arguments (e.g. `base_url`, `endpoint`, `model`, `request_timeout`). |

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

#### Actions

`actions:` is a list. Each entry is either an inline action **map** or, when reusing a shared action, a bare **string** referring to the shared action's name.

Inline action (map) fields:

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Action name (unique within the agent). |
| `function` | yes | Fully-qualified callable in the form `<module-or-class>:<qualname>`. See [Function references](#function-references). |
| `listen_to` | yes | List of event types the action listens to. Built-in [event aliases](#event-aliases) (`input`, `chat_request`, ...) or your own event-type strings. |
| `type` | no | Implementation language: `python` or `java`. Defaults to `python` (see [Selecting the implementation language](#selecting-the-implementation-language)). |
| `config` | no | Free-form configuration map passed to the action at runtime. |

```yaml
actions:
  - name: action1
    function: my_pkg.actions:action1
    listen_to: [input]
    type: python
  - name: action2
    function: my_pkg.actions:action2
    listen_to: [chat_response]
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
        thinking: false

  - name: agent2
    description: The second agent
    chat_model_setups:
      - name: my_llm
        clazz: ollama
        connection: my_connection      # same shared connection reused
        thinking: true

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
        listen_to: [input]

  - name: agent2
    description: The second agent
    actions:
      - action1                        # same shared action reused

# shared actions at the file level
actions:
  - name: action1
    function: my_pkg.actions:action1
    listen_to: [input]
    type: python
  - name: action2
    function: my_pkg.actions:action2
    listen_to: [chat_response]
    type: python
```

{{< hint info >}}
Composing one agent across multiple YAML files is **not** supported. If two files loaded into the same environment declare the same agent name, or the same shared resource/action name, the second `load_yaml` call raises an error.
{{< /hint >}}

## Loading and Running

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

A common pattern is to split a topology file (the agents themselves) from an infrastructure file (chat-model connections, vector stores, ...). The infrastructure file can be swapped per environment (dev / staging / prod) without touching the agent definitions.

For an end-to-end runnable walkthrough that loads a YAML-declared agent and runs it on Flink, see [YAML Agent Quickstart]({{< ref "docs/get-started/quickstart/yaml_agent" >}}).

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

Every resource, tool, and action accepts an optional `type:` field with values `python` or `java`. When omitted, the default is **`python`** — both the Python loader and the Java loader treat a missing `type:` as `python`.

This means a YAML file loaded by the **Java loader** must set `type: java` explicitly on every resource, tool, and action whose implementation is in Java. Omitting `type:` on a Java resource in a Java-loaded YAML will make the loader try to build a Python-wrapping descriptor — usually not what you want.

When `type:` resolves to the **opposite** language of the loader, the loader builds a cross-language descriptor that delegates to the other-language implementation — see [Cross-language agents](#cross-language-agents).

### Provider aliases

For `clazz:` on resource descriptors and for event names in `listen_to:`, you can use a short alias instead of a fully-qualified class path.

#### Event aliases

| Alias                          | Event type                       |
| ------------------------------ | -------------------------------- |
| `input`                        | `InputEvent`                     |
| `output`                       | `OutputEvent`                    |
| `chat_request`                 | `ChatRequestEvent`               |
| `chat_response`                | `ChatResponseEvent`              |
| `tool_request`                 | `ToolRequestEvent`               |
| `tool_response`                | `ToolResponseEvent`              |
| `context_retrieval_request`    | `ContextRetrievalRequestEvent`   |
| `context_retrieval_response`   | `ContextRetrievalResponseEvent`  |

For custom event types defined in your code, write the event's full `EVENT_TYPE` string instead of an alias.

#### Class aliases

Aliases for `clazz:` are keyed on resource type **and** implementation language. The same alias (e.g. `ollama`) resolves to a different class for `chat_model_connections` vs `chat_model_setups`, and for `python` vs `java`.

Common chat-model aliases:

| Alias                | `type: python`              | `type: java`                |
| -------------------- | --------------------------- | --------------------------- |
| `ollama`             | Ollama (Python)             | Ollama (Java)               |
| `openai`             | OpenAI Completions (Python) | —                           |
| `openai_completions` | —                           | OpenAI Completions (Java)   |
| `openai_responses`   | —                           | OpenAI Responses (Java)     |
| `anthropic`          | Anthropic                   | Anthropic                   |
| `azure_openai`       | Azure OpenAI (Python)       | —                           |
| `azure`              | —                           | Azure OpenAI (Java)         |
| `tongyi`             | Tongyi (Python)             | —                           |

Embedding-model and vector-store aliases follow the same scheme. The full alias tables live in `flink_agents.api.yaml.aliases` (Python) and `org.apache.flink.agents.api.yaml.Aliases` (Java).

If `clazz:` is not a known alias, the loader passes it through as-is — write a fully-qualified class path (e.g. `flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup`) for providers you've added yourself.

### Cross-language agents

Setting `type:` to the **opposite** language of the loader bridges Python and Java pieces in the same agent. The loader resolves the alias against the cross-language bucket and wraps the resource in the appropriate cross-language proxy.

Example — a Python-side agent whose chat model uses a **Java** Ollama setup that also calls a **Java** function tool:

```yaml
agents:
  - name: cross_language_agent
    actions:
      - name: process_input
        function: my_pkg.actions:process_input
        listen_to: [input]
      - name: process_chat_response
        function: my_pkg.actions:process_chat_response
        listen_to: [chat_response]

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

Not every resource type is cross-language. Currently `actions`, `tools`, `chat_model_connections`, `chat_model_setups`, `embedding_model_connections`, `embedding_model_setups`, and `vector_stores` support `type:` on the opposite language; others (e.g. `mcp_servers`) do not.

## YAML API Specification

To help users and coding agents understand the YAML format and validate YAML files, Flink Agents publishes a language-neutral **JSON Schema** for the YAML document. The schema is checked in at [`docs/yaml-schema.json`](https://github.com/apache/flink-agents/blob/main/docs/yaml-schema.json) — point your IDE's YAML language server at it for inline validation and autocompletion.

The schema benefits both humans and machines:

- It defines an interface contract, so LLMs and external systems can interact with Flink Agents declaratively.
- It is language-neutral, so it doubles as the compatibility contract between the Python and the Java loader.

### Python and Java mapping

When loading a YAML file, both runtimes parse it into typed in-memory representations and validate it against them using the framework's native validation:

- The **Python loader** parses YAML into Pydantic `BaseModel`s declared in `flink_agents.api.yaml.specs`. Pydantic enforces the schema (required fields, value types, `extra="forbid"` on unknown keys, mutually-exclusive `text`/`messages` on prompts, ...).
- The **Java loader** parses YAML into POJOs declared in `org.apache.flink.agents.api.yaml.spec`, validated by Jackson with equivalent rules.

### Consistency guarantees

Because Pydantic models are easier to author and evolve than raw JSON Schema, the **ground truth** is the Pydantic spec — the checked-in `docs/yaml-schema.json` is exported from it. Continuous tests then verify cross-runtime consistency:

- the JSON Schema exported by the Pydantic specs matches the checked-in `docs/yaml-schema.json`;
- the JSON Schema exported by the Java POJOs matches the checked-in `docs/yaml-schema.json`;
- the Pydantic specs stay aligned with the Python `Agent` API;
- the Java POJOs stay aligned with the Java `Agent` API.

This keeps the YAML API a true cross-language contract: a YAML file that validates against the schema is guaranteed to load and run on either the Python or the Java loader (subject only to the language-specific differences documented above, such as `parameter_types` on Java tools).
