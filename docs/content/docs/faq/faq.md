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

## Q1: What are the Python environment considerations when using Python with Flink Agents?

When using a PyFlink job, Python components, or another Flink Agents feature that
uses Python, follow these Python environment guidelines:

- **Recommended Python versions**: It is advised to use officially supported Python versions such as Python 3.10, 3.11 or 3.12. These versions have been thoroughly tested and offer the best compatibility with Flink Agents.

  {{< hint info >}}
  **Note**: Python 3.12 must work with Flink above 2.1 (including 2.1).
  {{< /hint >}}

- **Installation recommendations**:
    - **For Linux users**: We recommend installing Python via your system package manager (e.g., using `apt`: `sudo apt install python3`).
    - **For macOS users**: Use Homebrew to install Python (e.g., `brew install python`).
    - **For Windows users**: Download and install the official version from the [Python website](https://www.python.org/downloads/).

- **Avoid using Python installed via uv**: Currently, it is not recommended to use a Python interpreter installed via the `uv` tool with Flink Agents, as this may lead to potential compatibility issues or instability. For example, you may encounter the following gRPC-related error:

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

## Q2: How do I run an Agent in an IDE?

To avoid potential conflict with Flink cluster, the scope of the dependencies related to Flink and Flink Agents for agent job are provided. See [Maven Dependencies]({{< ref "docs/get-started/setup#maven-dependencies-for-java" >}}) for details.

To run the examples in IDE, users must enable the IDE feature: `add dependencies with provided scope to classpath`.
* For **IDEA**, edit the **`Run/Debug Configuration`** and enable **`add dependencies with provided scope to classpath`**. See [Run/Debug Configuration](https://www.jetbrains.com/help/idea/run-debug-configuration-scala.html) for details.
