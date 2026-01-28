---
title: 'General FAQ'
weight: 1
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
# General FAQ

This page describes the solutions to some common questions for Flink Agents users.

## Q1: What are the Python environment considerations when running Flink Agents jobs?

To ensure stability and compatibility when running Flink Agents jobs, please be aware of the following Python environment guidelines:

- **Recommended Python versions**: It is advised to use officially supported Python versions such as Python 3.10 or 3.11. These versions have been thoroughly tested and offer the best compatibility with Flink Agents.

- **Installation recommendations**:
    - **For Linux users**: We recommend installing Python via your system package manager (e.g., using `apt`: `sudo apt install python3`).
    - **For macOS users**: Use Homebrew to install Python (e.g., `brew install python`).
    - **For Windows users**: Download and install the official version from the [Python website](https://www.python.org/downloads/).

- **Avoid using Python installed via uv**: Currently, it is not recommended to run Flink Agents jobs with a Python interpreter installed via the `uv` tool, as this may lead to potential compatibility issues or instability. For example, you may encounter the following gRPC-related error:

    ```
    Logging client failed: <_MultiThreadedRendezvous of RPC that terminated with:
        status = StatusCode.UNAVAILABLE
        details = "Socket closed"
        debug_error_string = "UNKNOWN:Error received from peer ipv6:%5B::1%5D:58663 {grpc_message:"Socket closed", grpc_status:14}"
    >... resetting
    Exception in thread read_grpc_client_inputs:
    ...
    py4j.protocol.Py4JError: An error occurred while calling o12.execute
    ```

  If you see an error like this, switch immediately to one of the officially recommended installation methods and confirm that you're using a supported Python version.

## Q2: Why do cross-language resources not work in local development mode?

Cross-language resources, such as using Java resources from Python or vice versa, are currently supported only when running in Flink. Local development mode does not support cross-language resources.

This limitation exists because cross-language communication requires the Flink runtime environment to effectively bridge Java and Python processes. In local development mode, this bridge is unavailable.

To use cross-language resources, please test the functionality by deploying to a Flink standalone cluster.

## Q3: Should I choose Java or Python?

When choosing between Flink Agents' Java API and Python API, besides the team's experience and preferences, there's another thing needs to be considered. Flink Agents provides built-in integration supports for many echosystem providers. Some of these supports are in only one language. While you can still use them when building agents in another language, leveraging Flink Agents' cross-language supports, this comes with a limitation of not supporting async execution, which may bring performance concerns. 

The following matrix shows the native integration support status of providers over languages. For those marked as ❌, cross-language is needed thus async execution is not supported.

**Chat Models**

| provider | Python | Java |
|---|---|---|
| [OpenAI]({{< ref "docs/development/chat_models#openai" >}}) | ✅ | ✅ |
| [Anthropic]({{< ref "docs/development/chat_models#anthropic" >}}) | ✅ | ❌ |
| [Ollama]({{< ref "docs/development/chat_models#ollama" >}}) | ✅ | ✅ |
| [Tongyi (DashScope)]({{< ref "docs/development/chat_models#tongyi-dashscope" >}}) | ✅ | ❌ |
| [Azure AI]({{< ref "docs/development/chat_models#azure-ai" >}}) | ❌ | ✅ |

**Embedding Models**

| provider | Python | Java |
|---|---|---|
| [OpenAI]({{< ref "docs/development/embedding_models#openai" >}}) | ✅ | ❌ |
| [Ollama]({{< ref "docs/development/embedding_models#ollama" >}}) | ✅ | ✅ |

**Vector Stores**

| provider | Python | Java |
|---|---|---|
| [Chroma]({{< ref "docs/development/vector_stores#chroma" >}}) | ✅ | ❌ |
| [Elasticsearch]({{< ref "docs/development/vector_stores#elasticsearch" >}}) | ❌ | ✅ |

**MCP Server**

| provider | Python | Java 17+ | Java 11-16 |
|---|---|---|---|
| [MCP Server]({{< ref "docs/development/mcp" >}}) | ✅ | ✅ | ❌ |

Java native MCP support requires **JDK 17+**. See [MCP]({{< ref "docs/development/mcp" >}}) for details.

## Q4: How to run agent in IDE.

To avoid potential conflict with Flink cluster, the scope of the dependencies related to Flink and Flink Agents for agent job are provided. See [Maven Dependencies]({{< ref "docs/get-started/installation#maven-dependencies-for-java" >}}) for details.

To run the examples in IDE, users must enable the IDE feature: `add dependencies with provided scope to classpath`.
* For **IDEA**, edit the **`Run/Debug Configuration`** and enable **`add dependencies with provided scope to classpath`**. See [Run/Debug Configuration](https://www.jetbrains.com/help/idea/run-debug-configuration-scala.html) for details.