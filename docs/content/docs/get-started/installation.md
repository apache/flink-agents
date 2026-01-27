---
title: 'Installation'
weight: 2
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
Flink Agents provides both Python and Java APIs to define a Flink Agents job.

The sections below show how to install the required dependencies.

{{< hint warning >}}
__NOTE:__ To run on flink cluster, Flink-Agents requires flink version be a stable release of Flink 1.20.3.
{{< /hint >}}

## Install Apache Flink

Before installing Flink Agents, you need to have Apache Flink 1.20.3 installed.

Download and extract Flink 1.20.3:

```shell
# Download Flink 1.20.3: https://www.apache.org/dyn/closer.lua/flink/flink-1.20.3/flink-1.20.3-bin-scala_2.12.tgz
curl -LO https://archive.apache.org/dist/flink/flink-1.20.3/flink-1.20.3-bin-scala_2.12.tgz

# Extract the archive
tar -xzf flink-1.20.3-bin-scala_2.12.tgz

# Set FLINK_HOME environment variable
export FLINK_HOME=$(pwd)/flink-1.20.3

# Copy the flink-python JAR from opt to lib (required for PyFlink)
cp $FLINK_HOME/opt/flink-python-1.20.3.jar $FLINK_HOME/lib/
```

{{< hint info >}}
**Note:** For more detailed Flink installation instructions, refer to the [Flink local installation guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/).
{{< /hint >}}

## Install Flink Agents

### Prerequisites

* Python 3.10 or 3.11
* For building from source, you also need:
  - Unix-like environment (Linux, Mac OS X, Cygwin, or WSL)
  - Git
  - Maven 3
  - Java 17+ (full functionality), or Java 11+ (some features unavailable)

### Java Versions

For running an agent built with **Python API**, you can use any Java version 11 or higher.

When using **Java API**, there are some functionality limitations for earlier Java versions, as detailed below:

| Java Version | Limitations                   |
|--------------|-------------------------------|
| Java 17+     | No limitations.               |
| Java 11-16   | MCP supports are unavailable. |

### Set Up Python Environment (Recommended)

We recommend using a Python virtual environment to isolate Flink Agents dependencies from your system Python packages.

**Create a virtual environment:**

Using `venv` (built-in with Python 3):
```shell
# Create a virtual environment in a directory named 'venv'
python3 -m venv venv

# Activate the virtual environment
# On Linux/macOS:
source venv/bin/activate
# On Windows:
# venv\Scripts\activate
```

{{< hint info >}}
**Note:** If `python3` command is not found, try `python` instead. Some systems alias `python` to Python 3.
{{< /hint >}}

**To deactivate when you're done:**
```shell
deactivate
```

### Install Flink Agents Package

Choose one of the following installation methods:

#### From Official Release

{{< hint warning >}}
__Note:__ This will be available after Flink Agents is released.
{{< /hint >}}

Install Flink Agents using pip:

```shell
pip install flink-agents
```

#### From Source

**Clone the repository:**

```shell
git clone https://github.com/apache/flink-agents.git
cd flink-agents
```

**Build and install:**

Run the build script to build both Java and Python components:

```shell
./tools/build.sh
```

This script will:
- Build all Java modules using Maven
- Build the Python package
- Install the Python package into your current Python environment
- Package the distribution JAR with all dependencies

{{< hint info >}}
**Note:** If you activated a virtual environment earlier, the Python package will be installed into that virtual environment. Otherwise, it will be installed into your system Python environment.
{{< /hint >}}

After building:
- The Python package is installed and ready to use
- The distribution JAR is located at: `dist/target/flink-agents-dist-*.jar`

## Maven Dependencies (For Java)

For developing Flink Agents applications in Java, add the following dependencies to your `pom.xml`:

**Required**
- **`flink-agents-api`** : Flink Agents API
- **`flink-streaming-java`** and/or **`flink-table-api-java`** : Flink DataStream and/or Table API

**Optional**
- **`flink-agents-ide-support`** : Runtime execution dependencies (required for local execution/testing).
  - Unlike running in a Flink cluster, when running in IDE, because the runtime dependencies are absent, user need additional dependencies.
    To simplify the complexity for adding multiple dependencies in pom, flink-agents provide the above artifact.
    This way, users only need to add this single dependency in their pom.xml.

{{< hint info >}}
All the above dependencies should be in provided scope, to avoid potential conflict with the Flink cluster.

For execution in IDE, enable the feature `add dependencies with provided scope to classpath` in your IDE. See [FAQ]({{< ref "docs/faq/faq#q4-how-to-run-agent-in-ide" >}}) for details. 
{{< /hint >}}

**Example `pom.xml`**

```xml
<properties>
    <flink.version>2.2</flink.version>
    <flink-agents.version>0.2-SNAPSHOT</flink-agents.version>
</properties>

<dependencies>
    <!-- Flink Agents Core API -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-agents-api</artifactId>
        <version>${flink-agents.version}</version>
        <scope>provide</scope>
    </dependency>
    <!-- Flink Core API -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-streaming-java</artifactId>
        <version>${flink.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-table-api-java</artifactId>
        <version>${flink.version}</version>
        <scope>provided</scope>
    </dependency>

    <!-- Dependencies required for running agents in IDE -->
    <dependency>
      <groupId>org.apache.flink</groupId>
      <artifactId>flink-agents-ide-support</artifactId>
      <version>${project.version}</version>
      <scope>provided</scope>
    </dependency>
</dependencies>
```

## Deploy to Flink Cluster

After installing Flink Agents package, you need to deploy it to your Flink cluster so that Flink can run your agent jobs.

### Configure PYTHONPATH

Flink runs in its own JVM process and needs the `PYTHONPATH` environment variable to locate the flink-agents Python package. You need to set `PYTHONPATH` to the directory where flink-agents is installed.

**Determine your Python package installation path:**

The path depends on your Python environment setup:
- If using a virtual environment, it's the site-packages directory within your venv
- If using system Python, it's the system site-packages directory

**Tip:** You can use this command to help find the path:
```shell
python3 -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])'
```

**Set PYTHONPATH before starting Flink:**

```shell
# Set PYTHONPATH to your Python site-packages directory

export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
```

{{< hint info >}}
**Note:** You can add the `export PYTHONPATH=...` line to your shell profile (`~/.bash_profile`, `~/.bashrc`, `~/.zprofile`, or `~/.zshrc`) to set it permanently. This way, it will be automatically configured in all future terminal sessions.
{{< /hint >}}

### Install Flink Agents Java Library

Copy the Flink Agents distribution JAR to your Flink installation's `lib` directory:

{{< tabs "Install Flink Agents Java Library" >}}

{{< tab "From Official Release" >}}
The Flink Agents JAR is bundled inside the Python package. Use the PYTHONPATH you configured above to locate and copy it:

```shell
# Copy the JAR from the Python package to Flink's lib directory
cp $PYTHONPATH/flink_agents/lib/flink-agents-dist-*.jar $FLINK_HOME/lib/
```

{{< /tab >}}

{{< tab "From Source" >}}
After building from source, the distribution JAR is located in the `dist/target/` directory:

```shell
# Copy the JAR to Flink's lib directory
cp dist/target/flink-agents-dist-*.jar $FLINK_HOME/lib/
```
{{< /tab >}}

{{< /tabs >}}

### Start Flink Cluster or Submit Job

Once the Java library is installed and PYTHONPATH is configured, you can start your Flink cluster or submit jobs:

```shell
# Start your Flink cluster
$FLINK_HOME/bin/start-cluster.sh

# Or submit your job directly
$FLINK_HOME/bin/flink run -py /path/to/your/job.py
```

See [deployment]({{< ref "docs/operations/deployment" >}}) for more details on running Flink Agents jobs.