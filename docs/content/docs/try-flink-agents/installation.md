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

<!-- TODO: fill in the command after Flink Agent is released -->
```shell
# Download the Flink Agents released tar

# After downloading and extracting the Flink Agents release bundle,
# copy the Flink Agents JARs into Flink's lib directory

```

<!-- TODO: link to flink quickstart example docs -->
See [Flink quickstart example]() for end-to-end examples of running on Flink.


## Build and install from source

Prerequisites for building Flink Agents:

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Maven
* Java 11
* Python 3 (3.9, 3.10, 3.11 or 3.12)

To clone from Git, run:

```shell
git clone https://github.com/apache/flink-agents.git
```
### Python Build and Install

{{< tabs>}}
{{< tab "uv (Recommended)" >}}

```shell
cd python

# Install uv (fast Python package manager)
pip install uv

# Create env and install build dependencies
uv sync --extra build

# Build sdist and wheel into python/dist/
uv run python -m build

# Install the built wheel into the environment
uv pip install dist/*.whl
```

{{< /tab >}}

{{< tab "pip" >}}

```shell
cd python

# Install project (editable) with 'build' extra/tools
pip install -e .[build]

# Build sdist and wheel into python/dist/
python -m build

# Install the built wheel into the environment
python -m pip install dist/*.whl
```

{{< /tab >}}
{{< /tabs >}}

### Java Build and Install

To build the Java modules, run:

```shell
cd flink-agents
mvn clean install -DskipTests
```

To install the Java dependencies into Flink, run:

```shell
export FLINK_HOME=/path/to/flink
cp api/target/flink-agents-api-*.jar "$FLINK_HOME/lib/"
cp plan/target/flink-agents-plan-*.jar "$FLINK_HOME/lib/"
cp runtime/target/flink-agents-runtime-*.jar "$FLINK_HOME/lib/"
```
