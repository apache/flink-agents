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

#### Using uv (Recommended)

Firstly, install uv and build dependencies:

```shell
pip install uv
cd python
uv sync --extra build
```

Then build the package:

```shell
uv run python -m build
```

#### Using pip (Traditional)

Alternatively, you can use traditional pip:

```shell
cd python
pip install -e .[build]
python -m build
```

The sdist and wheel package of flink-agents will be found under `./python/dist/`. Either of them could be
used for installation:

```shell
# Using uv
uv pip install python/dist/*.whl

# Using pip
python -m pip install python/dist/*.whl
```

> **Note**: The `requirements/*.txt` files are deprecated. Please use the modern `pyproject.toml` 
> dependency groups. See [python/MIGRATION_GUIDE.md](python/MIGRATION_GUIDE.md) for details.

## How to Contribute

[Contribution Guidelines](.github/CONTRIBUTING.md).

## Community

### Slack

See the [Apache Flink website](https://flink.apache.org/what-is-flink/community/#slack) for how to join the slack workspace. We use [#flink-agents-dev](https://apache-flink.slack.com/archives/C097QF5HG8J) for developement related discussions.

### Community Sync

There is a weekly online sync. Everyone is welcome to join. Please find the schedule, agenda for the next sync, and records of previous syncs in this [github discussion page](https://github.com/apache/flink-agents/discussions/66).