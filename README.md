# Apache Flink Agents

Apache Flink Agents is an Agentic AI framework based on Apache Flink.

[User Documentation](https://nightlies.apache.org/flink/flink-agents-docs-main/)

## Building

Prerequisites for building Flink Agents:

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Maven
* Java 11
* Python 3 (3.10, 3.11)

To clone from git, enter:

```shell
git clone https://github.com/apache/flink-agents.git
```

We provide a script to build Flink-Agents from source

```shell
# Build java and python
./tools/build.sh
```

This scrips will build both java and python part, and install the Flink-Agents dist jar to python wheel package.

## How to Contribute

[Contribution Guidelines](.github/CONTRIBUTING.md).

## Community

### Slack

See the [Apache Flink website](https://flink.apache.org/what-is-flink/community/#slack) for how to join the slack workspace. We use [#flink-agents-user](https://apache-flink.slack.com/archives/C09KP5YUWE8) for user-facing discussions and trouble-shootings, and [#flink-agents-dev](https://apache-flink.slack.com/archives/C097QF5HG8J) for developement related discussions.

### Community Sync

There is a weekly online sync. Everyone is welcome to join. Please find the schedule, agenda for the next sync, and records of previous syncs in this [github discussion page](https://github.com/apache/flink-agents/discussions/66).