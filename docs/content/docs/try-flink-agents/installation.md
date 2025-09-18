---
title: 'Installation'
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

# Installation

Flink Agents provides both Python and Java APIs to define a Flink Agents job.

To try Flink Agents in Python on a local executor, you only need to install the Flink Agents Python package.

To define a Flink Agents job using the Java API, or to run the job in a Flink cluster, you need to install both the Flink Agents Python and Java dependencies. 

The sections below show how to install the required dependencies.

## Install from PyPI

{{< hint warning >}}
__Note:__ This will be available after Flink Agents is released.
{{< /hint >}}

To install the latest Flink Agents release, run:

```shell
python -m pip install flink-agents
```

<!-- TODO: link to local quickstart example docs -->
Now you can run a Flink Agents job on the local executor.
See [local quickstart example]() for end-to-end examples of running on the local executor.


To run on a Flink cluster, ensure the Flink Agents Java JARs are placed in the Flink lib directory:

<!-- TODO: fill in the command after Flink Agents is released -->
```shell
# Download the Flink Agents released tar

# After downloading and extracting the Flink Agents release bundle,
# copy the Flink Agents JARs to Flink's lib directory

```

<!-- TODO: link to flink quickstart example docs -->
See [Flink quickstart example]() for end-to-end examples of running on Flink.


## Build and Install from Source

Prerequisites for building Flink Agents:

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Maven
* Java 11
* Python 3.10 or 3.11

To clone from Git, run:

```shell
git clone https://github.com/apache/flink-agents.git
```

### Java Build

The Python wheel depends on the Flink Agents Java modules, so we need to build Flink Agents Java modules first, run:

```shell
cd flink-agents
mvn clean install -DskipTests
```

### Python Build and Install

Then we can build and install the Flink Agents wheel.

{{< tabs>}}
{{< tab "uv (Recommended)" >}}

uv is a modern, fast Python package manager that offers significant performance 
improvements over pip. 

If uv is not installed already, you can install it with the following command:

```shell
pip install uv
```
Please see [uv installation](https://docs.astral.sh/uv/getting-started/installation) for more detail.

```shell
cd python

# Build sdist and wheel into python/dist/
uv run python -m build

# Install the built wheel into the environment
uv pip install dist/*.whl
```

{{< /tab >}}

{{< tab "pip" >}}

We also support building and installing with pip

```shell
cd python

# Build sdist and wheel into python/dist/
python -m build

# Install the built wheel into the environment
python -m pip install dist/*.whl
```

{{< /tab >}}
{{< /tabs >}}

### Install Flink Agents to Flink


To install the Java dependencies to Flink, run:

<!-- TODO: fill in the command after Flink Agents produce uber jar -->
```shell

# copy the Flink Agents JARs to Flink's lib directory
```
