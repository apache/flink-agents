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

When choosing between Flink Agents' Java API and Python API, consider the following factors:

1. **Team experience and preferences**
2. **JDK version** (for Java users)
3. **Integration supports**

### Understanding Async Execution and JDK Versions

Async execution can significantly improve performance by allowing multiple operations to run concurrently. However, async execution support varies by language and JDK version:

| Environment | Async Execution Support |
|-------------|------------------------|
| Python | ✅ Supported |
| Java (JDK 21+) | ✅ Supported (via Continuation API) |
| Java (JDK < 21) | ❌ Not supported (falls back to synchronous execution) |

> **Cross-language async limitation**: When using cross-language resources (e.g., calling Java integrations from Python or vice versa), async execution is not supported. Cross-language calls always execute synchronously regardless of your JDK version.

This is important because:

- **For Python users**: Async execution is always available.
- **For Java users on JDK 21+**: Async execution is available, so using native integrations (instead of cross-language) matters for performance.
- **For Java users on JDK < 21**: Async execution is **not available regardless of whether you use native or cross-language integrations**. Therefore, the cross-language async limitation has **no additional performance impact** for these users.

### Native Integration Support Matrix

Flink Agents provides built-in integrations for many ecosystem providers. Some integrations are only available in one language. For those marked as ❌, you can still use them from the other language via cross-language support, but cross-language calls do not support async execution.

**Chat Models**

| Provider | Python | Java |
|---|---|---|
| [Anthropic]({{< ref "docs/development/chat_models#anthropic" >}}) | ✅ | ❌ |
| [Azure AI]({{< ref "docs/development/chat_models#azure-ai" >}}) | ❌ | ✅ |
| [Azure OpenAI]({{< ref "docs/development/chat_models#azure-openai" >}}) | ✅ | ❌ |
| [Ollama]({{< ref "docs/development/chat_models#ollama" >}}) | ✅ | ✅ |
| [OpenAI]({{< ref "docs/development/chat_models#openai" >}}) | ✅ | ✅ |
| [Tongyi (DashScope)]({{< ref "docs/development/chat_models#tongyi-dashscope" >}}) | ✅ | ❌ |

**Embedding Models**

| Provider | Python | Java |
|---|---|---|
| [Ollama]({{< ref "docs/development/embedding_models#ollama" >}}) | ✅ | ✅ |
| [OpenAI]({{< ref "docs/development/embedding_models#openai" >}}) | ✅ | ❌ |

**Vector Stores**

| Provider | Python | Java |
|---|---|---|
| [Chroma]({{< ref "docs/development/vector_stores#chroma" >}}) | ✅ | ❌ |
| [Elasticsearch]({{< ref "docs/development/vector_stores#elasticsearch" >}}) | ❌ | ✅ |

**MCP Server**

| Provider | Python | Java |
|---|---|---|
| [MCP Server]({{< ref "docs/development/mcp" >}}) | ✅ | ✅ |

> **Note**: Java native MCP support requires **JDK 17+**. For JDK 11-16, the framework automatically uses the Python SDK via cross-language support, which works seamlessly without additional configuration. See [MCP]({{< ref "docs/development/mcp" >}}) for details.

## Q4: How to run agent in IDE.

To avoid potential conflict with Flink cluster, the scope of the dependencies related to Flink and Flink Agents for agent job are provided. See [Maven Dependencies]({{< ref "docs/get-started/installation#maven-dependencies-for-java" >}}) for details.

To run the examples in IDE, users must enable the IDE feature: `add dependencies with provided scope to classpath`.
* For **IDEA**, edit the **`Run/Debug Configuration`** and enable **`add dependencies with provided scope to classpath`**. See [Run/Debug Configuration](https://www.jetbrains.com/help/idea/run-debug-configuration-scala.html) for details.