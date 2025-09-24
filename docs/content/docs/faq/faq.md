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
