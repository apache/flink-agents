# Apache Flink Agents

Apache Flink Agents is an Agentic AI framework based on Apache Flink.

## Building

Prerequisites for building Flink Agents:

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Maven
* Java 11
* Python 3 (3.9, 3.10, 3.11 or 3.12)

To clone from git, enter:

```shell
git clone https://github.com/apache/flink-agents.git
```

### Java Build

To build Flink Agents Java part, run:

```shell
cd flink-agents
mvn clean install -DskipTests
```

### Python Build

#### Using uv (Recommended) — Build and Install

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


#### Using pip (Alternative) — Build and Install

```shell
cd python

# Install project (editable) with 'build' extra/tools
pip install -e .[build]

# Build sdist and wheel into python/dist/
python -m build

# Install the built wheel into the environment
python -m pip install dist/*.whl
```


## How to Contribute

[Contribution Guidelines](.github/CONTRIBUTING.md).

## Community

### Slack

See the [Apache Flink website](https://flink.apache.org/what-is-flink/community/#slack) for how to join the slack workspace. We use [#flink-agents-dev](https://apache-flink.slack.com/archives/C097QF5HG8J) for developement related discussions.

### Community Sync

There is a weekly online sync. Everyone is welcome to join. Please find the schedule, agenda for the next sync, and records of previous syncs in this [github discussion page](https://github.com/apache/flink-agents/discussions/66).