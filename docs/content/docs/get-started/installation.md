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
__NOTE:__ To run on a Flink cluster, Flink-Agents requires a stable release of Flink version 1.20.3 or higher. We highly recommend using the **latest stable release of your chosen Flink minor version** (e.g., for minor version 2.2, use the latest 2.2.x release).
{{< /hint >}}

## Prerequisites

Both the script-based and manual installation paths require:

* **Java 11+** on your `PATH`. Java 21+ is recommended when using the Java API — see the table below for the version-specific limitations.
* **Python 3.10, 3.11, or 3.12** (only required if you plan to use the Python API or PyFlink).

For building Flink Agents from source, you additionally need:
- Unix-like environment (Linux, macOS, Cygwin, or WSL)
- Git
- Maven 3

### Java Versions

For running an agent built with the **Python API**, you can use any Java version 11 or higher.

When using the **Java API**, there are some functionality limitations for earlier Java versions, as detailed below:

| Java Version | Limitations            |
|--------------|------------------------|
| Java 21+     | No limitations.        |
| Java 11-20   | Async execution is unavailable. |

{{< hint info >}}
**Note**: Python 3.12 requires Flink 2.1 or above and Flink Agents 0.3 or above.
{{< /hint >}}

## Quick install (recommended)

The `install.sh` script provisions everything you need: it downloads Apache Flink, creates a Python virtual environment, installs `flink-agents` and `apache-flink` into it, and copies the required JARs into `$FLINK_HOME/lib`.

Run the one-liner from any directory you want the virtual environment created in:

```shell
curl -fsSL https://raw.githubusercontent.com/apache/flink-agents/main/tools/install.sh | bash
```

## Manual installation

Use this path when you want full control over each step, when running `install.sh` is not feasible in your environment, or when building Flink Agents from source.

### Install Apache Flink

Before installing Flink Agents, you need to have Apache Flink installed.

Download and extract Flink:

```shell
# 1. Go to https://flink.apache.org/downloads/ and download the latest stable release of your desired Flink version
# 2. Set the Flink version you downloaded (e.g., 2.2.x)
export FLINK_VERSION=<version>

# 3. Extract the archive
tar -xzf flink-${FLINK_VERSION}-bin-scala_2.12.tgz

# Set FLINK_HOME environment variable
export FLINK_HOME=$(pwd)/flink-${FLINK_VERSION}

# Copy the flink-python JAR from opt to lib (required for PyFlink)
cp $FLINK_HOME/opt/flink-python-${FLINK_VERSION}.jar $FLINK_HOME/lib/
```

{{< hint info >}}
**Note:** For more detailed Flink installation instructions, refer to the [Flink local installation guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/).
{{< /hint >}}

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

Install Flink Agents using pip:

```shell
# Install Flink Agents and Flink Python package
pip install flink-agents apache-flink==${FLINK_VERSION}
```

{{< hint warning >}}
**Apple Silicon (macOS arm64) + Python 3.12**: `apache-flink` depends on `apache-beam`, which ships no macOS arm64 wheel for Python 3.12, so pip builds it from source. The build pulls the latest `setuptools` (>=82), which removed `pkg_resources`, causing `ModuleNotFoundError: No module named 'pkg_resources'` (an upstream issue — see [setuptools#5174](https://github.com/pypa/setuptools/issues/5174)). Constrain the build's setuptools to fix it:

```shell
echo "setuptools<82" > /tmp/constraint.txt
PIP_CONSTRAINT=/tmp/constraint.txt pip install flink-agents apache-flink==${FLINK_VERSION}
```
{{< /hint >}}

#### From Source

**Clone the repository:**

```shell
git clone https://github.com/apache/flink-agents.git
```

**Build and install:**

Run the build script to build both Java and Python components:

```shell
# Install Flink Python package
pip install apache-flink==${FLINK_VERSION}

# Build Flink Agents
cd flink-agents
./tools/build.sh
cd ..
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
- The distribution JAR is located at: `dist/flink-${FLINK_VERSION%.*}/target/flink-agents-dist-*.jar`

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

### Install Flink Agents Java Library (Java jobs only)

{{< hint info >}}
**Skip this section if you only use the Python API.** When you call
`AgentsExecutionEnvironment.get_execution_environment(env=...)`, the framework
automatically registers the required JARs via `pipeline.jars` so Flink loads
them into the user-code classloader. No manual JAR copy into
`$FLINK_HOME/lib/` is needed.
{{< /hint >}}

This step is for Java jobs (or other deployments that don't go through the
Python entry point). Copy the Flink Agents distribution JAR to your Flink
installation's `lib` directory:

{{< tabs "Install Flink Agents Java Library" >}}

{{< tab "From Official Release" >}}
Download the published JAR from Maven Central into Flink's `lib` directory.
Set `FLINK_AGENTS_VERSION` to the release you want (e.g. `0.2.1`):

```shell
export FLINK_AGENTS_VERSION=<version>
export FLINK_MAJOR_MINOR=${FLINK_VERSION%.*}

curl -fL \
  "https://repo1.maven.org/maven2/org/apache/flink/flink-agents-dist-flink-${FLINK_MAJOR_MINOR}/${FLINK_AGENTS_VERSION}/flink-agents-dist-flink-${FLINK_MAJOR_MINOR}-${FLINK_AGENTS_VERSION}.jar" \
  -o "$FLINK_HOME/lib/flink-agents-dist-flink-${FLINK_MAJOR_MINOR}-${FLINK_AGENTS_VERSION}.jar"
```

{{< /tab >}}

{{< tab "From Source" >}}
After building from source, copy the self-contained distribution JAR
(without the `-thin` suffix) to Flink's `lib` directory:

```shell
# Set the Flink Agents version (from the version you built, e.g. 0.3-SNAPSHOT)
export FLINK_AGENTS_VERSION=<version>

cp dist/flink-${FLINK_VERSION%.*}/target/flink-agents-dist-flink-${FLINK_VERSION%.*}-${FLINK_AGENTS_VERSION}.jar $FLINK_HOME/lib/
```
{{< /tab >}}

{{< /tabs >}}

### Build Environment Variables

The following environment variables can be used to control how JARs are resolved during `pip install flink-agents` (from sdist) or `python -m build`:

| Variable | Description |
|----------|-------------|
| `FLINK_AGENTS_SKIP_JAR_DOWNLOAD` | Set to `1`, `true`, `yes`, or `on` (case-insensitive) to skip downloading JARs from Maven Central. Useful when building from source with `tools/build.sh`, which copies JARs from the local Maven build. |
| `FLINK_AGENTS_MAVEN_MIRROR` | Override the Maven repository base URL for JAR downloads. Defaults to `https://repo1.maven.org/maven2`. Useful for environments with restricted network access or internal mirrors. |

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
    <flink.version>2.2.1</flink.version>
    <flink-agents.version>0.3.0</flink-agents.version>
</properties>

<dependencies>
    <!-- Flink Agents Core API -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-agents-api</artifactId>
        <version>${flink-agents.version}</version>
        <scope>provided</scope>
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

Once the Java library is installed and PYTHONPATH is configured, you can start your Flink cluster or submit jobs:

{{< tabs "Deploy to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
# Start your Flink cluster
$FLINK_HOME/bin/start-cluster.sh

# Or submit your job directly
$FLINK_HOME/bin/flink run -py /path/to/your/job.py
```

{{< /tab >}}

{{< tab "Java" >}}
```bash
# Start your Flink cluster
$FLINK_HOME/bin/start-cluster.sh

# Or submit your job directly
$FLINK_HOME/bin/flink run -c <your.main.class> /path/to/your-job.jar
```
{{< /tab >}}

{{< /tabs >}}

See [deployment]({{< ref "docs/operations/deployment" >}}) for more details on running Flink Agents jobs.