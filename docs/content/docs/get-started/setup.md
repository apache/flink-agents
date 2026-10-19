---
title: "Setup"
weight: 2
type: docs
aliases:
  - /docs/get-started/installation/
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

## Choose a Setup Path

An Agent runs inside a Flink job, so what you install follows that job. Two job types
exist: a Java Flink job and a PyFlink job. The job type is decided by how you submit the
job, not by the language you wrote the Agent in.

| Flink job | Submit with | Flink Agents JAR | Python environment |
|---|---|---|---|
| Java Flink job | `flink run -c <MainClass> job.jar` | You supply the distribution JAR yourself. | Needed only when the Agent runs a Python component. |
| PyFlink job | `flink run -py job.py` | Registered for you from the installed `flink-agents` package. | Required. |

A YAML Agent is not a third job type. A Java Flink job or a PyFlink job loads the YAML
file and submits the job, so install for whichever job loads it. An Agent can also mix
Actions and Resources written in both languages, and then the job needs both sets of
dependencies. See
[Use Java and Python Actions and Resources]({{< ref "docs/development/create-agent/choose-apis-and-languages#use-java-and-python-actions-and-resources" >}})
for which combinations are supported.

Two installation paths follow, and you pick one of them:

* **Quick install** runs `install.sh`, which provisions Flink, the JARs, and the Python
  environment for you.
* **Manual installation** places each piece yourself. Use it when the script cannot run
  in your environment, when you want control over each step, or when you build Flink
  Agents from source.

{{< hint warning >}}
__NOTE:__ To run on a Flink cluster, Flink Agents requires a stable release of Flink version 1.20.3 or higher. We highly recommend using the **latest stable release of your chosen Flink minor version** (e.g., for minor version 2.2, use the latest 2.2.x release). Some features need a higher patch release, such as [Cross-language Async Requirements]({{< ref "docs/development/create-agent/actions#cross-language-async-requirements" >}}).
{{< /hint >}}

## Prerequisites

Both installation paths above require:

* **Java 11+** on your `PATH`, using a JDK supported by your chosen Flink release. See [Java Versions](#java-versions) for feature-specific requirements.
* **Python 3.10, 3.11, or 3.12** when using a PyFlink job, Python Actions or Resources, or another feature that uses Python, such as the Python MCP SDK. This includes Java Flink jobs that use those components.

To build Flink Agents from source, you additionally need:
- Unix-like environment (Linux, macOS, Cygwin, or WSL)
- Git
- Maven 3

### Java Versions

The Flink Agents Java baseline is JDK 11. The JDK must also be supported by the
Flink release you deploy. Concurrency and integration requirements depend on the
feature:

- Java Action scheduling differs between JDK 21+ and earlier JDKs. See
  [JDK and Scheduling Requirements]({{< ref "docs/development/create-agent/actions#jdk-and-scheduling-requirements" >}})
  for configuration and the JDK 21+ JVM option.
- Java-native MCP requires JDK 17+; on earlier supported JDKs, it uses the Python SDK
  and requires its Python dependencies. See [MCP SDK]({{< ref "docs/development/calling-llms/mcp#mcp-sdk" >}}).

{{< hint info >}}
**Note**: Python 3.12 requires Flink 2.1 or above and Flink Agents 0.3 or above.
{{< /hint >}}

## Quick install (recommended)

The `install.sh` script downloads Apache Flink and copies the Flink Agents distribution JAR
into `$FLINK_HOME/lib`. When you enable PyFlink, it also creates a Python virtual environment
and installs `flink-agents` and `apache-flink` into it.

Run the one-liner from the directory where you want the virtual environment created:

```shell
curl -fsSL https://raw.githubusercontent.com/apache/flink-agents/main/tools/install.sh | bash
```

The script asks whether to install Flink and whether to enable PyFlink. Answer **Yes** to
PyFlink for a PyFlink job, and **No** for a Java-only setup. To run it without prompts, pass
`--non-interactive` together with `--install-flink` and the versions you want, and add
`--enable-pyflink` when you also need the Python environment:

```shell
curl -fsSL https://raw.githubusercontent.com/apache/flink-agents/main/tools/install.sh | bash -s -- \
  --non-interactive --install-flink --flink-version <flink-version> --flink-agents-version <flink-agents-version>
```

Run the script with `--dry-run` to print the plan without changing anything, and with
`--help` to list every option and environment variable. When it finishes, continue at
[Submit Your First Job](#submit-your-first-job).

## Manual installation

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

# Copy the flink-python JAR from opt to lib (required for PyFlink or Python components)
cp $FLINK_HOME/opt/flink-python-${FLINK_VERSION}.jar $FLINK_HOME/lib/
```

{{< hint info >}}
**Note:** For more detailed Flink installation instructions, refer to the [Flink local installation guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/).
{{< /hint >}}

### Set Up a Python Environment

Do this step for a PyFlink job, and for a Java Flink job whose Agent runs a Python
component. Skip it when no Python component is involved.

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
**Apple Silicon (macOS arm64)**: `apache-flink` depends on `apache-beam`, and none of the `apache-beam` versions that `apache-flink` 1.20 through 2.3 accept ships a macOS arm64 wheel, so pip builds it from source on every supported Python version. The build pulls the latest `setuptools` (>=82), which removed `pkg_resources`, causing `ModuleNotFoundError: No module named 'pkg_resources'`. That is an upstream issue, tracked in [setuptools#5174](https://github.com/pypa/setuptools/issues/5174). Constrain the build's setuptools to fix it:

```shell
echo "setuptools<82" > /tmp/constraint.txt
PIP_CONSTRAINT=/tmp/constraint.txt pip install flink-agents apache-flink==${FLINK_VERSION}
```
{{< /hint >}}

#### From Source

**Clone the repository:**

```shell
git clone --branch {{< branch >}} https://github.com/apache/flink-agents.git
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

On Apple Silicon, that `pip install` can fail while building `apache-beam`. See
the warning in [From Official Release](#from-official-release) for the fix.

This script will:
- Build all Java modules using Maven
- Build the Python package
- Install the Python package into `python/.venv` in the repository
- Package the distribution JAR with all dependencies

{{< hint info >}}
**Note:** `tools/build.sh` installs the Python package into `python/.venv` in the repository, not into an activated virtual environment. To use your own environment, install the built wheel into it from the repository root with `pip install python/dist/flink_agents-*.whl`.
{{< /hint >}}

After building, the Java build leaves two distribution JARs in
`dist/flink-${FLINK_VERSION%.*}/target/`, and the one a Java Flink job needs is the one without
the `-thin` suffix.

The Python package bundles the JARs it needs, so a PyFlink job does not use those build
outputs. See
[Install Flink Agents Java Library](#install-flink-agents-java-library-java-flink-jobs)
for the jobs that do.

### Build Environment Variables

The following environment variables can be used to control how JARs are resolved during `pip install flink-agents` (from sdist) or `python -m build`:

| Variable | Description |
|----------|-------------|
| `FLINK_AGENTS_SKIP_JAR_DOWNLOAD` | Set to `1`, `true`, `yes`, or `on` (case-insensitive) to skip downloading JARs from Maven Central. Useful when building from source with `tools/build.sh`, which copies JARs from the local Maven build. |
| `FLINK_AGENTS_MAVEN_MIRROR` | Override the Maven repository base URL for JAR downloads. Defaults to `https://repo1.maven.org/maven2`. Useful for environments with restricted network access or internal mirrors. |

### Configure PYTHONPATH

Do this step whenever the job needs a Python environment, which is every PyFlink job and
every Java Flink job whose Agent runs a Python component.

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

### Install Flink Agents Java Library (Java Flink jobs)

{{< hint info >}}
**Skip the manual JAR copy for PyFlink jobs.** When you call
`AgentsExecutionEnvironment.get_execution_environment(env=...)`, the framework
automatically registers the required JARs via `pipeline.jars` so Flink loads
them into the user-code classloader. No manual JAR copy into
`$FLINK_HOME/lib/` is needed.

The same call fails if the `flink-agents` package is missing its bundled JARs, so
install the Python package in the environment that submits the job. See
[Configure PYTHONPATH](#configure-pythonpath) for the environment Flink reads.
{{< /hint >}}

This step is for Java Flink jobs, including those that load YAML Agents or use Python
components. Copy the Flink Agents distribution JAR to your Flink installation's `lib`
directory:

{{< tabs "Install Flink Agents Java Library" >}}

{{< tab "From Official Release" >}}
Download the published JAR from Maven Central into Flink's `lib` directory.
Set `FLINK_AGENTS_VERSION` to the release you want (e.g. `0.3.0`):

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
# Set the Flink Agents version (from the version you built, e.g. 0.4-SNAPSHOT)
export FLINK_AGENTS_VERSION=<version>

cp dist/flink-${FLINK_VERSION%.*}/target/flink-agents-dist-flink-${FLINK_VERSION%.*}-${FLINK_AGENTS_VERSION}.jar $FLINK_HOME/lib/
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
**Note:** The quick install performs this step for you, so you can skip it if you ran
`install.sh`.
{{< /hint >}}

## Developing with the Java API

To develop a Java Flink job, add the Flink Agents artifacts to its compile classpath.

### Maven Dependencies

Add the following dependencies to your `pom.xml`:

**Required**
- **`flink-agents-api`** : Flink Agents API
- **`flink-streaming-java`** and/or **`flink-table-api-java`** : Flink DataStream and/or Table API

**Optional**
- **`flink-agents-ide-support`** : the runtime execution dependencies, needed to run an
  Agent locally or in an IDE. A Flink cluster gets the runtime from the distribution JAR
  in `$FLINK_HOME/lib`, so this artifact is not needed there. It bundles the same runtime
  dependencies so you can add one dependency instead of several.

{{< hint info >}}
All the above dependencies should be in provided scope, to avoid potential conflict with the Flink cluster.

For execution in IDE, enable the feature `add dependencies with provided scope to classpath` in your IDE. See [FAQ]({{< ref "docs/faq/faq#q2-how-do-i-run-an-agent-in-an-ide" >}}) for details.
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
      <version>${flink-agents.version}</version>
      <scope>provided</scope>
    </dependency>
</dependencies>
```

## Submit Your First Job

Start a local cluster and submit the job through the entry point you installed for:

{{< tabs "Submit Your First Job" >}}

{{< tab "Python" >}}
```bash
# Required: lets the Flink JVM locate the flink-agents package
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

$FLINK_HOME/bin/start-cluster.sh

$FLINK_HOME/bin/flink run -py /path/to/your/job.py
```

{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/start-cluster.sh

$FLINK_HOME/bin/flink run -c <your.main.class> /path/to/your-job.jar
```
{{< /tab >}}

{{< /tabs >}}

The shortest way to a working job is to run a Quickstart end to end, because each one
submits a complete example and shows its output:

- [Build an Agent]({{< ref "docs/get-started/quickstart/create-agent" >}})
- [Use the Built-in ReAct Agent]({{< ref "docs/get-started/quickstart/react_agent" >}})
- [Define an Agent with YAML]({{< ref "docs/get-started/quickstart/yaml_agent" >}})

For a remote cluster, and for the consistency guarantees that apply once the job runs
there, see [Deployment]({{< ref "docs/operations/deployment" >}}).
