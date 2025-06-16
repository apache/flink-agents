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

Firstly, you need to install the dependencies with following command:

```shell
python -m pip install -r python/requirements/build_requirements.txt
```

Secondly, you can build Flink Agents Python sdist and wheel packages with following command:

```shell
python -m build python
```

The sdist and wheel package of flink-agents will be found under ./python/dist/. Either of them could be
used
for installation, such as:

```shell
python -m pip install python/dist/*.whl
```

## How to Contribute

[Contribution Guidelines](.github/CONTRIBUTING.md).