# Project Generation and MiniCluster Validation

## Contents

- [Delivery Contract](#delivery-contract)
- [Resolve and Confirm a Compatible Version Set](#resolve-and-confirm-a-compatible-version-set)
- [Generate a Java Maven Project](#generate-a-java-maven-project)
- [Generate a Python Project and Select an Environment](#generate-a-python-project-and-select-an-environment)
- [Handle Local Plaintext Credentials](#handle-local-plaintext-credentials)
- [Scaffold Runtime Skill Configuration](#scaffold-runtime-skill-configuration)
- [Connect the Agent through RemoteExecutionEnvironment](#connect-the-agent-through-remoteexecutionenvironment)
- [Submit to a Local MiniCluster](#submit-to-a-local-minicluster)

## Delivery Contract

Do not stop after generating an Agent class or YAML definition. For a new
application, also generate and verify its executable project:

- Java: a complete Maven project with `pom.xml`, sources, Resources, a Flink job
  entry point, and a configured local run command;
- Python: source files, pinned dependency input, `.gitignore`, a user-selected
  existing Python environment or project-local `.venv`, installed dependencies, and
  a Flink job entry point;
- both: a `StreamExecutionEnvironment`, the public Agents factory backed by
  `RemoteExecutionEnvironment`, a keyed DataStream/Table, a sink, and an
  `AgentsExecutionEnvironment.execute(...)` call.

For existing applications, preserve their package manager and layout, but provide
the same runnable path. "Local" changes only the Flink deployment target: the job
is still built through `RemoteExecutionEnvironment` and submitted to a MiniCluster.
Never substitute the removed local Agents APIs.

## Resolve and Confirm a Compatible Version Set

For an existing application, resolve versions from its lockfile, Maven metadata,
installed package, or `FLINK_HOME`, preserve them, and report what was detected. For
a new application, do not create dependency files, a virtual environment, or source
files until all sequential gates are complete. Versions are the first gate; do not
choose or mention the implementation language before it.

Offer choices rather than asking an open-ended version question. The publication
snapshot, mirrored from `tools/install.sh`, is:

| Component | Installer-supported versions | New-project choices | Recommended choice |
|---|---|---|---|
| Flink Agents | `0.3.0`, `0.2.1`, `0.2.0`, `0.1.1`, `0.1.0` | `0.3.0`, `0.2.1`, `0.1.1` | `0.3.0` |
| Flink | `2.2.1`, `2.1.3`, `2.0.2`, `1.20.5` | `2.2.1`, `2.1.3`, `2.0.2`, `1.20.5` | `2.2.1` |

For a new project, offer only the highest supported patch in each Flink Agents
minor line. Older patches such as `0.2.0` and `0.1.0` remain valid when an existing
project pins them or the user explicitly requests one, but they add no useful
choice to the default new-project menu.

Render the Flink Agents row through the interaction adapter selected by `SKILL.md`.
If the native tool limits the number of options, use the adapter's hierarchy or
paging rule so every version remains selectable. First satisfy any mode prerequisite
defined by that adapter. If the native tool remains unavailable afterward, use a
numbered fallback instead of asking the user to type a version string:

```text
Select the Flink Agents version:
1. 0.3.0 (Recommended)
2. 0.2.1
3. 0.1.1

Reply with 1, 2, or 3.
```

After that answer, present the compatible Flink versions through a separate native
selector or numbered list. Never put both version decisions into one multi-select
control: they are ordered single-choice gates because the first filters the second.

Flink Agents `0.1.x` supports Flink `1.20` only in this snapshot. Flink Agents
`0.2.x` and `0.3.x` publish artifacts for all listed Flink minors. Use a
target-version installer, release metadata, or compatibility matrix when available
because it overrides this bundled snapshot.

Present the recommended choice first, but do not select it because the user is
silent. Ask only for the Flink Agents version and wait. Then show only the Flink
versions compatible with that answer and wait again. These questions must not also
propose YAML/Python/Java APIs, Python/JDK versions, model providers, Resource
configuration, Skill loading, business backends, or mocks. Summarize only the
confirmed Flink Agents/Flink pair before moving to the API gate.

Do not describe a bundled recommendation as "latest", "preview", or preferable to
an unlisted Flink release unless target-version release metadata was actually
checked and that comparison is needed for the user's request. The installer choices
are a compatibility menu, not permission to make broader release claims.

After the API and implementation language are confirmed, resolve the language
runtime separately. When the design first requires Python, inspect available
interpreters and environments and keep only versions compatible with the already
selected Flink pair; do not assume Python 3.12. In this snapshot, Python 3.12
requires Flink Agents `0.3+` and Flink `2.1+`; older combinations require Python
3.10 or 3.11. Then ask whether to reuse one specific compatible environment or
create a project-local `.venv` from a compatible interpreter. This conditional gate
also fires when Java application code selects a Python Resource. For Java, detect or
ask for a compatible JDK only after Java application code is selected. Keep these
values aligned:

- the exact Flink patch version used for local execution;
- the Flink Agents Java or Python package version;
- every Flink Agents module and integration version;
- the user-confirmed Python or Java application-code language;
- each selected Resource implementation language and bridge dependency;
- the Java and Python versions supported by that Flink Agents release.

The selected Flink binary may require a newer Java runtime; use the higher
requirement from Flink and Flink Agents. Do not leave guessed versions or unresolved
version placeholders in the generated project.

Resolve integration dependencies from each Resource's implementation language, not
from the application language. A Java application using a bridge-supported Python
chat model, embedding model, or vector store still needs the corresponding Python
package in every TaskManager environment. A Java implementation selected by a
Python application still needs its Java integration JAR on the runtime classpath.
Generate both sides of that dependency contract and the target-version bridge
runtime; do not hide the foreign-language dependency or replace the Resource.

## Generate a Java Maven Project

Generate at least:

```text
agent-app/
├── pom.xml
└── src/
    ├── main/java/<package>/{Actions,Tools,Main}.java
    ├── main/resources/agent.yaml
    └── test/java/<package>/
```

Use the Flink Agents application modules directly. Always add
`flink-agents-api`, `flink-agents-plan`, and `flink-agents-runtime`; add only the
integration artifacts required by the Resources declared in the application. Do
not use a dist artifact or `flink-agents-ide-support` as the application's compile
contract.

Declare every Flink Agents and Flink dependency with `provided` scope. The target
deployment must supply the matching Flink Agents modules, integrations, and Flink
runtime; `provided` is a packaging contract, not an automatic cluster install. The
generated forked local-run configuration includes these dependencies through
Maven's compile classpath.

```xml
<properties>
    <flink.version>RESOLVE_EXACT_FLINK_VERSION</flink.version>
    <flink-agents.version>RESOLVE_EXACT_AGENTS_VERSION</flink-agents.version>
    <maven.compiler.release>RESOLVE_COMPATIBLE_JAVA_RELEASE</maven.compiler.release>
    <main.class>RESOLVE_MAIN_CLASS</main.class>
    <maven-compiler-plugin.version>3.14.1</maven-compiler-plugin.version>
    <exec-maven-plugin.version>3.6.3</exec-maven-plugin.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-agents-api</artifactId>
        <version>${flink-agents.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-agents-plan</artifactId>
        <version>${flink-agents.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-agents-runtime</artifactId>
        <version>${flink-agents.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-streaming-java</artifactId>
        <version>${flink.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-clients</artifactId>
        <version>${flink.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-table-api-java-bridge</artifactId>
        <version>${flink.version}</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>${maven-compiler-plugin.version}</version>
            <configuration>
                <release>${maven.compiler.release}</release>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>${exec-maven-plugin.version}</version>
            <configuration>
                <executable>${java.home}/bin/java</executable>
                <classpathScope>compile</classpathScope>
                <arguments>
                    <argument>-classpath</argument>
                    <classpath/>
                    <argument>${main.class}</argument>
                </arguments>
            </configuration>
        </plugin>
    </plugins>
</build>
```

For each selected integration, add its target-version artifact with the same
Flink Agents version and `provided` scope. Resolve its actual artifact from
target-version metadata only after the user confirms the Resource implementation;
do not select an integration from an example and do not add every integration
preemptively. The Table bridge is required even for the default DataStream job
because the `AgentsExecutionEnvironment` API and runtime constructor reference
`StreamTableEnvironment`. Add the matching Table planner only for Table API jobs,
and add connector dependencies only for the selected sources and sinks. Declare
those Flink artifacts as `provided` too.

Generate a complete POM, not just the fragment. The bundled plugin versions above
are a verified baseline; preserve a compatible newer version already selected by
the target project. Run the main class in a forked JVM with compile classpath scope:

```bash
mvn clean compile exec:exec
```

Do not use `exec:java` as the default. Its in-process plugin ClassLoader can make a
Flink MiniCluster fail while deserializing operator factories. `exec:exec` starts a
normal JVM with the generated dependency classpath.

Keep `<classpathScope>compile</classpathScope>` in the forked local-run
configuration: Maven's compile classpath includes `provided` dependencies, so the
same POM supports both local smoke tests and cluster-oriented packaging without a
second dependency profile.

## Generate a Python Project and Select an Environment

Generate at least:

```text
agent-app/
├── .gitignore
├── requirements.txt
├── agent.yaml
├── actions.py
├── tools.py
└── main.py
```

Pin the resolved versions in `requirements.txt`:

```text
flink-agents==RESOLVE_EXACT_AGENTS_VERSION
apache-flink==RESOLVE_EXACT_FLINK_VERSION
```

Include any application-specific dependencies used by Actions or Tools. Before
installing them, inspect compatible local Python executables and environments. If
the existing project does not already declare one unambiguously, present a closed
choice that names concrete paths, for example:

1. `Create .venv with /path/to/python3.11 (Recommended)`
2. `Reuse /path/to/existing/environment/bin/python`

If several compatible existing environments or base interpreters exist, use the
host adapter's hierarchy so the user selects an exact executable. Do not create the
environment, install dependencies, or silently use the active shell Python before
the answer.

For a project-local environment, create and populate it with the selected base
interpreter:

```bash
<selected-base-python> -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m pip check
```

For an existing environment, do not create `.venv`; use the exact selected
executable for installation and verification:

```bash
<selected-existing-python> -m pip install -r requirements.txt
<selected-existing-python> -m pip check
```

On Windows, use the selected executable path such as `.venv\Scripts\python`. Add
`.venv/` to `.gitignore` only when it is created, and always ignore caches, logs,
and local secrets. Use the selected executable for every later import, test, build,
and local job command. If an existing interpreter rejects installation because it
is externally managed or read-only, report that result and return to the environment
choice instead of bypassing its protection or silently creating a venv.

The `flink-agents` Python package carries the common and Flink-version-specific
Flink Agents JARs. Constructing `AgentsExecutionEnvironment` registers those JARs
with the Flink pipeline, so a Python application must not copy another Flink Agents
dist JAR into the project. The generated Action modules must still be importable
from the working directory or installed application package.

## Handle Local Plaintext Credentials

Do not ask the user to choose a credential mechanism while scaffolding a Resource.
Generate required credential keys with `TODO_REQUIRED_*` values. Plaintext YAML is
still a valid local-testing mechanism when the user explicitly requests it or
provides a value; do not reject that instruction or replace it with Java/Python
`addResource` wiring. Put a real value only in a clearly local file such as
`config/agent.local.yaml`, add its exact path to `.gitignore`, and make the local
Java or Python entry point load that file. Keep a tracked secret-free example only
when it helps the user reproduce the layout.

If the project is a Git worktree, verify the protection before running:

```bash
git check-ignore -v config/agent.local.yaml
git status --short
```

The first command must identify the intended ignore rule, and the secret-bearing
file must not appear as untracked or staged in the second command. If the workspace
does not use Git, state that repository-level protection could not be verified.

Do not ask for the value. When the user supplies one without prompting, write it
only to the ignored local file. Do not place it in a shell command, generated test
fixture, tracked example, Maven configuration, dependency file, console output,
diff excerpt, or final report. Warn once that the file contains plaintext and is for
local testing; then continue with the verification that its filled configuration
permits.

## Scaffold Runtime Skill Configuration

For an existing application, preserve and validate its configured runtime Skill
source. For a new application, do not ask the user to choose distribution and do
not inspect or reuse coding-agent host Skills. Generate a runtime `SKILL.md` TODO
shell and an unresolved source declaration/factory TODO. The following table tells
the user what they can fill later:

| Choice | Generated project requirement |
|---|---|
| Bundle with Java application | Use YAML `classpath` or `Skills.fromClasspath`; place Skill directories under `src/main/resources/<resource-path>` and verify the built JAR contains them |
| Bundle with Python application | Use YAML `package` or `Skills.from_package`; generate an installable Python package, include the Skill tree as package data, build or install it into the selected Python environment, and verify the resource is readable from that installed package |
| TaskManager-local path | Use YAML `paths` or the language's local-dir factory; accept directories or ZIPs and document the path that every TaskManager must mount or provision |
| Versioned HTTP(S) ZIP | Use YAML `urls` or the language's URL factory; require a ZIP whose top level contains Skill directories and document TaskManager network access |

Do not copy a local `flink-diag` or any Skill found under Codex, Claude Code, Qoder,
Gemini CLI, or another coding-agent installation. The generated runtime Skill shell
belongs to the user's application and contains only capability-derived metadata plus
a TODO body.

Do not package, mount, or download the runtime Skill until the user fills the source.
If the user later chooses bundled Python Skills, use a package layout such as:

```text
agent-app/
├── pyproject.toml
└── src/app/
    ├── __init__.py
    └── resources/skills/<skill-name>/SKILL.md
```

Then configure the selected build backend to include every Skill Markdown file, script,
and reference as package data. Install the application package with the same
selected Python executable used to run the job, for example with
`<selected-python> -m pip install -e .`, then verify the configured `package` and
`resource` pair through the installed package-resource API. Deploy that package to
every Python worker in the target cluster; installation in the selected local
environment proves only local availability. Do not point `package` at an uninstalled
source directory.

Do not treat implementation language as the distribution decision: Java and Python
may both use `paths` or `urls`. Use those portable schemes for an intentional
cross-language source. If multiple YAML source fields are explicitly combined,
preserve loader order `paths`, `urls`, `classpath`, `package` and reject duplicate
Skill names rather than depending on last-wins replacement.

A local MiniCluster shares one machine and can validate artifact contents, ZIP
shape, and local resolution. It cannot prove that a distributed cluster mounts the
same path or allows every TaskManager to reach a URL. Report those as deployment
requirements unless they were verified in the target cluster environment. Prefer
immutable, versioned URLs; the current YAML contract has no checksum field.

## Connect the Agent through RemoteExecutionEnvironment

Both Python and Java must create a Flink `StreamExecutionEnvironment` first, then
pass it to the public `AgentsExecutionEnvironment` factory. In the supported API,
that factory creates `RemoteExecutionEnvironment`; use the public factory instead
of importing the runtime implementation directly.

Never call the Python factory without `env`, and never use a local Agents
environment, `from_list`/`to_list`, or Java list equivalents. Local validation means
the remote-style Flink job is submitted to the MiniCluster selected by the local
`StreamExecutionEnvironment`.

Use a bounded source so the validation command terminates. Apply a YAML Agent by its
declared name; apply a programmatic Agent by instance.

Python shape:

```python
from pathlib import Path

from pyflink.common import Types
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.execution_environment import AgentsExecutionEnvironment


env = StreamExecutionEnvironment.get_execution_environment()
env.set_parallelism(1)
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
agents_env.load_yaml(Path(__file__).with_name("agent.yaml"))

input_stream = env.from_collection(
    ["local smoke test"], type_info=Types.STRING()
)
output_stream = (
    agents_env.from_datastream(input_stream, key_selector=lambda value: value)
    .apply("agent_name")
    .to_datastream()
)
output_stream.print()
agents_env.execute("Flink Agent MiniCluster Validation")
```

Java shape:

```java
StreamExecutionEnvironment env =
        StreamExecutionEnvironment.getExecutionEnvironment();
env.setParallelism(1);
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);
agentsEnv.loadYaml(Paths.get("src/main/resources/agent.yaml"));

DataStream<String> input = env.fromElements("local smoke test");
DataStream<Object> output =
        agentsEnv
                .fromDataStream(
                        input, (KeySelector<String, String>) value -> value)
                .apply("agent_name")
                .toDataStream();
output.print();
agentsEnv.execute("Flink Agent MiniCluster Validation");
```

Adapt types, key selection, Agent name, and paths to the generated application.
Use a key that is stable for all events belonging to one Agent invocation. A
continuous file or message source may be added separately, but it must not replace
the terminating smoke-test path.

The non-empty examples above are behavior smoke tests and require implemented
business functions. When custom Action or Tool bodies are intentionally still
scaffolds, do not invoke them with fabricated behavior. A deployment-only check may
use a typed empty bounded source, such as Python
`env.from_collection([], type_info=Types.STRING())` or Java
`env.fromCollection(Collections.emptyList(), Types.STRING)`, while preserving the
Agent operator and sink. Report that this validates job construction, submission,
and deployment only; it does not validate Action, Tool, model, or output behavior.

## Submit to a Local MiniCluster

Python, using the environment selected earlier:

```bash
<selected-python> -c "import flink_agents; import pyflink"
<selected-python> main.py
```

Java:

```bash
mvn dependency:tree
mvn clean compile exec:exec
```

Running these commands directly uses Flink's local MiniCluster while retaining the
same `RemoteExecutionEnvironment` integration used for cluster submission. Before a
behavior smoke test, configure only the user-confirmed model/backend and credential
mechanism. Do not generate or select a test double to fill an unspecified business
implementation. If the user has not implemented the business bodies or configured
the external Resource, run deployment-only validation and state what blocks behavior
or integration validation.

Report evidence at the correct level:

- deployment validation: the bounded job was submitted to the MiniCluster and
  finished; an empty source is acceptable when business functions are scaffolds;
- behavior validation: non-empty input invoked the implemented business functions,
  produced the expected sink record, and finished;
- integration validation: the external model, MCP server, vector store, or service
  was actually reached.

A compile, YAML load, or successful deployment-only job is not proof of business or
external integration behavior.
