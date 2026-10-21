---
title: "Resources"
weight: 4
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

Resources are named capabilities that Actions or other Resources use, such as chat
models, embedding models, vector stores, tools, prompts, and Skills. Register the
Resources your Agent needs, then reference them by name and resource type.

Each feature guide describes its own configuration and operations. This page covers
the registration, scope, access, and cross-language mechanisms they share.

## Resource Types

`ResourceType` identifies the capability a Resource provides. Java and Python use
the following type names:

| Resource type | Purpose | Guide |
|---|---|---|
| `CHAT_MODEL_CONNECTION` | Connect to a chat model service, including endpoint and authentication settings. | [Chat Models]({{< ref "docs/development/calling-llms/chat-models" >}}) |
| `CHAT_MODEL` | Configure a chat model setup: connection, model, prompt, tools, and generation parameters. | [Chat Models]({{< ref "docs/development/calling-llms/chat-models" >}}) |
| `DECISION_MODEL_CONNECTION` | Connect to a decision model service. | [Decision Models]({{< ref "docs/development/decision_models" >}}) |
| `DECISION_MODEL` | Configure a decision model setup: connection and model. | [Decision Models]({{< ref "docs/development/decision_models" >}}) |
| `EMBEDDING_MODEL_CONNECTION` | Connect to an embedding model service. | [Embedding Models]({{< ref "docs/development/vector-search/embedding_models" >}}) |
| `EMBEDDING_MODEL` | Configure an embedding model setup for converting text into vectors. | [Embedding Models]({{< ref "docs/development/vector-search/embedding_models" >}}) |
| `VECTOR_STORE` | Store and retrieve documents using vector search. | [Vector Stores]({{< ref "docs/development/vector-search/vector_stores" >}}) |
| `PROMPT` | Provide a reusable text or message template. | [Prompts]({{< ref "docs/development/calling-llms/prompts" >}}) |
| `TOOL` | Expose a callable operation that a model or Action can invoke. | [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}) |
| `MCP_SERVER` | Connect to an MCP server to access its tools and prompts. | [MCP]({{< ref "docs/development/calling-llms/mcp" >}}) |
| `SKILLS` | Provide skill instructions and supporting assets loaded from configured sources. | [Skills]({{< ref "docs/development/calling-llms/skills" >}}) |
| `MODEL_ROUTER` | Select a chat model from configured candidates for a request. | [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}}) |
| `AGENT` | Expose an internal or external sub-agent for delegation or direct calls. | [Sub-agents]({{< ref "docs/development/multi-agent/subagents" >}}) |

Registration support varies by type. In particular, `MODEL_ROUTER` currently
supports the Java programmatic API only; Python registration and YAML declarations
are not available. Refer to each guide for its supported APIs and configuration.

[Memory]({{< ref "docs/development/memory/overview" >}}) has a separate API and is
not a Resource type.

## Registering Resources

Register a Resource with a name and a resource type. The name is how Actions and
other Resources refer to it; the type identifies its role, such as `CHAT_MODEL`,
`TOOL`, or `PROMPT`.

The **agents execution environment** (`AgentsExecutionEnvironment`) is the entry
point for integrating Agents into a Flink job. It also holds shared Resource
registrations that multiple Agents in that environment can use.

| Declaration method | Registration | Scope |
|---|---|---|
| Declaration API | Use the Python decorator or Java annotation for the Resource type, such as `@chat_model_setup` / `@ChatModelSetup` for a chat model. The declaration's method name becomes the Resource name. | The Agent. |
| Programmatic API | Call `add_resource(...)` / `addResource(...)` on an Agent or the agents execution environment with an explicit name, resource type, and Resource instance or descriptor. | Agent-local when registered on an Agent; shared across Agents when registered on the environment. |
| YAML | Declare named entries in the appropriate Resource section, such as `chat_model_setups`, `tools`, or `prompts`. | Agent-local or shared, according to where they are declared. |

An Agent's own Resources take precedence over shared environment Resources with
the same name and type. See the [YAML Resource reference]({{< ref "docs/development/create-agent/yaml#resources" >}})
for declaration fields and examples, and
[shared Resources]({{< ref "docs/development/create-agent/yaml#declaring-and-reusing-shared-resources-and-actions" >}})
for environment-level YAML declarations.

The registration value depends on the Resource type. For example, a Prompt can be
registered as a serializable Resource instance. A chat model uses a
`ResourceDescriptor` to specify its implementation and configuration; the framework
creates the Resource instance at runtime. Each Resource's guide describes its
supported declarations and settings.

The following examples show three alternative ways to register an Ollama connection
and a chat model setup. They assume the Ollama server is running with `qwen3:8b`
available. Imports, Actions, and job setup are omitted to focus on Resource registration.

### Declaration API

Declare Resources on an Agent using Python decorators or Java annotations. The
method name becomes the Resource name, and the setup refers to its connection by
that name.

{{< tabs "Resource Declaration API" >}}
{{< tab "Python" >}}
```python
class ReviewAgent(Agent):
    @chat_model_connection
    @staticmethod
    def ollama_server() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
            base_url="http://localhost:11434",
        )

    @chat_model_setup
    @staticmethod
    def review_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
        )
```
{{< /tab >}}
{{< tab "Java" >}}
```java
public class ReviewAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor ollamaServer() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaServer")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }
}
```
{{< /tab >}}
{{< /tabs >}}

These declarations register the model as `review_model` in Python and `reviewModel`
in Java. Use the corresponding name when accessing it or sending a chat request.

### Programmatic API

Call `add_resource(...)` / `addResource(...)` with an explicit name, resource type,
and registration value. The example below creates an agents execution environment
from Flink's `StreamExecutionEnvironment`: `agents_env` in Python and `agentsEnv`
in Java are variables holding that `AgentsExecutionEnvironment` instance. It then
registers a shared connection on the environment and a model setup on an existing
Agent instance named `agent`.

{{< tabs "Programmatic Resource Registration" >}}
{{< tab "Python" >}}
```python
flink_env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(flink_env)

agents_env.add_resource(
    "ollama_server",
    ResourceType.CHAT_MODEL_CONNECTION,
    ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
        base_url="http://localhost:11434",
    ),
)
agent.add_resource(
    "review_model",
    ResourceType.CHAT_MODEL,
    ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="ollama_server",
        model="qwen3:8b",
    ),
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
StreamExecutionEnvironment flinkEnv = StreamExecutionEnvironment.getExecutionEnvironment();
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(flinkEnv);

agentsEnv.addResource(
        "ollama_server",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build());
agent.addResource(
        "review_model",
        ResourceType.CHAT_MODEL,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollama_server")
                .addInitialArgument("model", "qwen3:8b")
                .build());
```
{{< /tab >}}
{{< /tabs >}}

To keep the connection local to the Agent, register it on `agent` instead of the
environment. To share the setup as well, register it on `agents_env` / `agentsEnv`.
The explicit names are independent of Java or Python method names.
See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) for
adding the Agent to a DataStream or Table pipeline and running the job.

### YAML

Declare named Resources in the section for their resource type. This example
registers both Resources on `review_agent`, selecting the Python Ollama implementation
with `type: python` and the built-in `ollama` alias:

```yaml
agents:
  - name: review_agent
    chat_model_connections:
      - name: ollama_server
        clazz: ollama
        type: python
        base_url: http://localhost:11434
    chat_model_setups:
      - name: review_model
        clazz: ollama
        type: python
        connection: ollama_server
        model: qwen3:8b
```

For the Java Ollama implementation, set `type: java` on both entries and use
`endpoint` instead of `base_url` on the connection. To share Resources across Agents,
declare their sections at the file's top level. See the
[YAML Resource reference]({{< ref "docs/development/create-agent/yaml#resources" >}})
for fields and constraints, and [Loading and Running]({{< ref "docs/development/create-agent/yaml#loading-and-running" >}})
for loading the YAML into an agents execution environment.

## Referencing and Accessing Resources

A Resource's registered name identifies it inside your application. It is separate
from provider configuration: for example, `review_model` can name a chat model
Resource whose `model` setting selects `qwen3:8b` on an Ollama server.

Resources can reference other Resources through their configuration. A chat model
setup's `connection`, `prompt`, and `tools` fields refer to registered Resource
names. The appropriate resource type is determined by the field.

Inside an Action, use `RunnerContext` to get a Resource by name and type, then use
its methods. For the chat model registered with the programmatic API or YAML above:

{{< tabs "Access a Resource" >}}
{{< tab "Python" >}}
```python
model = ctx.get_resource("review_model", ResourceType.CHAT_MODEL)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
BaseChatModelSetup model =
        (BaseChatModelSetup) ctx.getResource("review_model", ResourceType.CHAT_MODEL);
```
{{< /tab >}}
{{< /tabs >}}

Built-in Actions perform some Resource operations for you. For example, a
`ChatRequestEvent` names a model; the built-in chat Action gets that Resource,
calls it, and sends a `ChatResponseEvent`. Your own Action does not need to get the
model directly. See [Chat with Models]({{< ref "docs/development/calling-llms/chat" >}})
and [Built-in Events and Actions]({{< ref "docs/development/create-agent/events#built-in-events-and-actions" >}})
for these workflows.

## Cross-Language Resources

An Agent can use supported Resource implementations from either Java or Python.
Chat model connections and setups, embedding model connections and setups, and
vector stores support cross-language use in both directions. Decision model
connections and setups support cross-language use only from Java to Python:
Java Agents can use Python implementations, but Python Agents cannot use Java
implementations. Within these limits, choose an implementation based on the
provider capabilities you need.

| Calling language | Descriptor for the other language | Implementation class |
|---|---|---|
| Python using a Java Resource | `JavaResourceDescriptor(clazz=..., ...)` | A fully-qualified Java class name, or a `ResourceName` constant such as `ResourceName.ChatModel.Java.OLLAMA_CONNECTION`. |
| Java using a Python Resource | `PythonResourceDescriptor.Builder.newBuilder(...)` | A fully-qualified Python class path including its module, or a `ResourceName` constant such as `ResourceName.ChatModel.Python.OLLAMA_CONNECTION`. |

Register the descriptor using the same name, resource type, and scope rules as
other Resources. Configuration keys follow the **implementation's** API, not the
language making the declaration. For example, the Java Ollama chat connection
uses `endpoint`, while its Python counterpart uses `base_url`.

In YAML, select the implementation with `type: java` or `type: python` and the
appropriate `clazz`; see [Selecting the implementation language]({{< ref "docs/development/create-agent/yaml#selecting-the-implementation-language" >}}).
Function Tools use language-specific function references rather than these provider
descriptors; see [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}})
and the [YAML cross-language example]({{< ref "docs/development/create-agent/yaml#cross-language-agents" >}}).

### Example: Use a Chat Model from the Other Language

The following alternatives use the Declaration API to register an Ollama chat
connection and setup on an Agent. They assume the Ollama server is running with `qwen3:8b`
available. Imports and job setup are omitted.

{{< tabs "Cross-Language Resource Registration" >}}
{{< tab "Python using Java" >}}
```python
class ReviewAgent(Agent):
    @chat_model_connection
    @staticmethod
    def chat_connection() -> ResourceDescriptor:
        return JavaResourceDescriptor(
            clazz=ResourceName.ChatModel.Java.OLLAMA_CONNECTION,
            endpoint="http://localhost:11434",
        )

    @chat_model_setup
    @staticmethod
    def review_model() -> ResourceDescriptor:
        return JavaResourceDescriptor(
            clazz=ResourceName.ChatModel.Java.OLLAMA_SETUP,
            connection="chat_connection",
            model="qwen3:8b",
        )
```

Inside a Python Action, request a response using the registered model name:

```python
ctx.send_event(
    ChatRequestEvent(
        model="review_model",
        messages=[ChatMessage.user("Summarize the benefits of streaming data.")],
    )
)
```
{{< /tab >}}
{{< tab "Java using Python" >}}
```java
public class ReviewAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor chatConnection() {
        return PythonResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.Python.OLLAMA_CONNECTION)
                .addInitialArgument("base_url", "http://localhost:11434")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor reviewModel() {
        return PythonResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.Python.OLLAMA_SETUP)
                .addInitialArgument("connection", "chatConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }
}
```

Inside a Java Action, request a response using the registered model name:

```java
ctx.sendEvent(
        new ChatRequestEvent(
                "reviewModel",
                List.of(ChatMessage.user("Summarize the benefits of streaming data."))));
```
{{< /tab >}}
{{< /tabs >}}

Handle the resulting `ChatResponseEvent` as shown in
[Chat with Models]({{< ref "docs/development/calling-llms/chat#usage-example" >}}).
The request/response flow is the same for native and cross-language models.

The same descriptor selection applies to embedding models and vector stores; their
resource types, configuration, and operations remain those described in their
feature guides. Cross-language access does not add capabilities to a provider.
For example, cross-language chat setups have
[structured-output restrictions]({{< ref "docs/development/calling-llms/structured-output#provider-support" >}}).

### Runtime Requirements

Install the selected Java and Python implementations and their dependencies in the
job environment. A descriptor does not install dependencies or start the provider
service. Follow [Integrate the Agent into a Flink job]({{< ref "docs/development/create-agent/choose-apis-and-languages#integrate-the-agent-into-a-flink-job" >}})
for packaging and runtime setup.

When Resource results are carried in Event payloads, follow the
[Java and Python data contracts]({{< ref "docs/development/create-agent/choose-apis-and-languages#data-passed-between-java-and-python" >}}).
For asynchronous calls, including built-in Actions that call Resources, follow the
[cross-language execution requirements]({{< ref "docs/development/create-agent/actions#cross-language-async-requirements" >}}).
These cover supported Flink versions and the limit on nested calls between languages.
