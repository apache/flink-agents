---
title: "Choose APIs and Languages"
weight: 5
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

Flink Agents provides Java, Python, and YAML APIs for defining an Agent. All three
APIs use the same core concepts: Actions process Events and use Resources.

Actions and Resources can be implemented in Java or Python and used across languages
where supported. Flink Agents aims to keep its APIs and component behavior consistent
across languages. However, language features, runtime requirements, and available
ecosystem integrations may lead to differences in capabilities and setup.

## Define an Agent

Choose one of the three APIs to define the Agent:

| API | How the Agent is defined | Guide |
|---|---|---|
| Java | Define an `Agent` class in Java. | [Create a Single Agent (Java)]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}}) |
| Python | Define an `Agent` class in Python. | [Create a Single Agent (Python)]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}}) |
| YAML | Describe the Agent in a YAML file. | [YAML API]({{< ref "docs/development/create-agent/yaml" >}}) |

## Use Java and Python Actions and Resources

The language used to define the Agent does not require every Action and Resource to
use the same language. A Java-defined Agent can use supported Python components, and
a Python-defined Agent can use supported Java components. In YAML, Actions, Tools,
and Resources configured by implementation class accept `type: java` or
`type: python`; an omitted `type` defaults to `python`. Prompts and Skills do not
accept `type`. See [Selecting the implementation language]({{< ref "docs/development/create-agent/yaml#selecting-the-implementation-language" >}}).

Cross-language support is available for the following component APIs:

| Component | Authoring guide |
|---|---|
| Actions | [Cross-language Actions]({{< ref "docs/development/create-agent/actions#cross-language-actions" >}}) |
| Function Tools | [YAML cross-language example]({{< ref "docs/development/create-agent/yaml#cross-language-agents" >}}) and [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}) |
| Chat models, embedding models, and vector stores | [Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}) |
| Decision models | [Decision Models]({{< ref "docs/development/decision_models" >}}) and [Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}) |

For YAML examples, see
[Cross-language agents]({{< ref "docs/development/create-agent/yaml#cross-language-agents" >}}).

## Integrate the Agent into a Flink job

An Agent runs as part of a Flink job. For Agents defined with the Java or Python API,
the API determines how they are added to Flink: a Java Agent is added to a Java Flink
job, and a Python Agent is added to a PyFlink job. YAML is flexible and can be loaded
from either type of job.

| Agent API | Flink job | How the Agent is added |
|---|---|---|
| Java | Java Flink job | Pass the Java `Agent` instance to `apply(...)`. |
| Python | PyFlink job | Pass the Python `Agent` instance to `apply(...)`. |
| YAML | Java Flink or PyFlink job | Load the YAML with `loadYaml(...)` or `load_yaml(...)`, then pass the Agent name to `apply(...)`. |

For a Java Flink job, add the
[Maven dependencies]({{< ref "docs/get-started/setup#maven-dependencies" >}})
and [Flink Agents Java runtime JAR]({{< ref "docs/get-started/setup#install-flink-agents-java-library-java-flink-jobs" >}}).
For a PyFlink job, install the
[Python package]({{< ref "docs/get-started/setup#install-flink-agents-package" >}})
and configure [PYTHONPATH]({{< ref "docs/get-started/setup#configure-pythonpath" >}}).
`AgentsExecutionEnvironment.get_execution_environment(env=...)` registers the Flink
Agents JARs bundled with the Python package.

When using components implemented in the other language, provide their dependencies
as well. For a PyFlink job that uses Java components, add the required application and
provider JARs. For a Java Flink job that uses Python components, install the Flink
Agents Python package, component modules, and provider dependencies in the Python
environment used for submission and on the Flink workers.

See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) for
DataStream and Table API usage, [Loading and Running YAML]({{< ref "docs/development/create-agent/yaml#loading-and-running" >}})
for YAML loading, and [Deployment]({{< ref "docs/operations/deployment#submit-to-flink-cluster" >}})
for submission commands.

## Differences between the Java and Python APIs

The Java and Python APIs provide the same core Agent model, but their Action syntax,
execution requirements, and available ecosystem integrations differ.

### Async Action execution

Both APIs provide asynchronous execution and `gather` for running multiple calls.
Python Actions use `async def` and `await`. Java Actions use
`executeAsync(...).await()`, and Action scheduling and required JVM options depend on
the JDK version. See [Async Execution]({{< ref "docs/development/create-agent/actions#async-execution" >}})
for call and batch examples, and
[Execution Requirements]({{< ref "docs/development/create-agent/actions#execution-requirements" >}})
for runtime prerequisites.

### Available integrations by language

The tables below show the available implementation languages for each built-in
integration. If an integration is available in only one language, use
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}})
where supported and install its dependencies.

**Chat Models**

| Provider | Python | Java |
|---|---|---|
| [Alibaba Cloud Model Studio (DashScope)]({{< ref "docs/development/calling-llms/chat-models/providers#alibaba-cloud-model-studio-dashscope" >}}) | ✅ | ❌ |
| [Amazon Bedrock]({{< ref "docs/development/calling-llms/chat-models/providers#amazon-bedrock" >}}) | ❌ | ✅ |
| [Anthropic]({{< ref "docs/development/calling-llms/chat-models/providers#anthropic" >}}) | ✅ | ✅ |
| [Azure OpenAI]({{< ref "docs/development/calling-llms/chat-models/providers#azure-openai" >}}) | ✅ | ✅ |
| [Gemini]({{< ref "docs/development/calling-llms/chat-models/providers#gemini" >}}) | ❌ | ✅ |
| [Ollama]({{< ref "docs/development/calling-llms/chat-models/providers#ollama" >}}) | ✅ | ✅ |
| [OpenAI Chat Completions]({{< ref "docs/development/calling-llms/chat-models/providers#completions-api" >}}) | ✅ | ✅ |
| [OpenAI Responses]({{< ref "docs/development/calling-llms/chat-models/providers#responses-api" >}}) | ❌ | ✅ |
| [vLLM]({{< ref "docs/development/calling-llms/chat-models/providers#vllm" >}}) | ✅ | ✅ |
| [Watsonx (IBM watsonx.ai)]({{< ref "docs/development/calling-llms/chat-models/providers#watsonx-ibm-watsonxai" >}}) | ✅ | ✅ |

**Embedding Models**

| Provider | Python | Java |
|---|---|---|
| [Alibaba Cloud Model Studio (DashScope)]({{< ref "docs/development/vector-search/embedding_models/providers#alibaba-cloud-model-studio-dashscope" >}}) | ✅ | ❌ |
| [Amazon Bedrock]({{< ref "docs/development/vector-search/embedding_models/providers#amazon-bedrock" >}}) | ❌ | ✅ |
| [Ollama]({{< ref "docs/development/vector-search/embedding_models/providers#ollama" >}}) | ✅ | ✅ |
| [OpenAI]({{< ref "docs/development/vector-search/embedding_models/providers#openai" >}}) | ✅ | ✅ |

**Vector Stores**

| Provider | Python | Java |
|---|---|---|
| [Amazon OpenSearch]({{< ref "docs/development/vector-search/vector_stores/providers#amazon-opensearch" >}}) | ❌ | ✅ |
| [Amazon S3 Vectors]({{< ref "docs/development/vector-search/vector_stores/providers#amazon-s3-vectors" >}}) | ❌ | ✅ |
| [Chroma]({{< ref "docs/development/vector-search/vector_stores/providers#chroma" >}}) | ✅ | ❌ |
| [Elasticsearch]({{< ref "docs/development/vector-search/vector_stores/providers#elasticsearch" >}}) | ❌ | ✅ |
| [Mem0]({{< ref "docs/development/vector-search/vector_stores/providers#mem0" >}}) | ✅ | ❌ |
| [Milvus]({{< ref "docs/development/vector-search/vector_stores/providers#milvus" >}}) | ❌ | ✅ |
| [PostgreSQL pgvector]({{< ref "docs/development/vector-search/vector_stores/providers#postgresql-pgvector" >}}) | ❌ | ✅ |

**Decision Models**

| Provider | Python | Java |
|---|---|---|
| [OpenAI]({{< ref "docs/development/decision_models" >}}) | ✅ | ❌ |
| [TypeSafe]({{< ref "docs/development/decision_models" >}}) | ✅ | ❌ |

**MCP Server**

| Provider | Python | Java |
|---|---|---|
| [MCP Server]({{< ref "docs/development/calling-llms/mcp" >}}) | ✅ | ✅ |

> **Note**: Java native MCP support requires **JDK 17+**. For JDK 11-16, the framework automatically uses the Python SDK via cross-language support, which requires a configured Python environment and the Python MCP dependencies. See [MCP]({{< ref "docs/development/calling-llms/mcp" >}}) for details.

## Cross-language requirements

Python Agents cross the Python-Java boundary when exchanging data with the Java
runtime. Agents that call components implemented in the other language also have
additional async execution requirements.

### Data passed between Java and Python

Event attributes passed between Java and Python, including `InputEvent.input` and
`OutputEvent.output`, support the following portable JSON value shapes:

| Value | Java representation | Python representation |
|---|---|---|
| Null | `null` | `None` |
| Boolean | `boolean` / `Boolean` | `bool` |
| Integer | Integral numbers, such as `Integer` or `Long` | `int` |
| Floating-point number | `Float` or `Double` | `float` |
| Text | `String` | `str` |
| Sequence | JSON arrays, read as `List` | `list` |
| Object / mapping | `Map<String, Object>` | `dict` with string keys |

Follow these rules:

- Sequences and mappings can contain any of the values in the table.
- Numeric wrapper types, collection classes, and custom classes are not preserved.
  JSON-serializable Java POJOs and Python models arrive as `Map` or `dict` values;
  reconstruct the target-language type explicitly. Objects that cannot be serialized
  to JSON are not supported.
- Keep integers within the target type's range and use finite floating-point values.
  Encode exact decimals and larger numbers as strings when precision must be preserved.

These conversion rules describe application payloads. Built-in Event classes and their
declared typed attributes are restored automatically at the JSON boundary; custom Event
subclasses require explicit reconstruction. See
[Serialization and Typed Values]({{< ref "docs/development/create-agent/events#json-serialization" >}})
for examples of restoring Event and payload types.

{{< hint info >}}
Flink Agents uses a Java runtime. Data from the Python API crosses the Python-Java
boundary even when every Action and Resource is implemented in Python. The rules in
this section therefore apply to every Python Agent.
{{< /hint >}}

#### Memory values

Python sensory and short-term memory accept built-in `None`, `bool`, `int`, `float`,
`str`, `bytes`, `list`, and string-keyed `dict` values recursively. Convert custom
models, tuples, sets, and enums before storing them. On this path, `bytes` maps to Java
`byte[]`; raw bytes are not supported in JSON Event attributes. See
[Sensory and Short-Term Memory]({{< ref "docs/development/memory/sensory_and_short_term_memory" >}}).

### Async calls between Java and Python

Async calls across Java and Python require a supported Flink version. Keep each
cross-language call chain to one reverse callback; deeper callback chains may deadlock.
See [Cross-language async requirements]({{< ref "docs/development/create-agent/actions#cross-language-async-requirements" >}})
for supported Flink versions and call shapes.
