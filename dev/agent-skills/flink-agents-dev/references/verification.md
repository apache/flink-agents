# Verification

## Contents

- [1. Version and Source](#1-version-and-source)
- [2. YAML Schema](#2-yaml-schema)
- [3. Static Reference Graph](#3-static-reference-graph)
- [4. Action and Event Graph](#4-action-and-event-graph)
- [5. Language Checks](#5-language-checks)
- [6. Resource-specific Checks](#6-resource-specific-checks)
- [7. Runtime Evidence](#7-runtime-evidence)
- [Final Report Template](#final-report-template)

Verify in layers. A later layer does not replace an earlier one.

## 1. Version and Source

- Identify the target Flink Agents, Flink, language, and provider versions from
  dependency metadata or installed packages.
- For a new project, record the user's explicit Flink Agents and Flink choices. A
  bundled recommended version without user confirmation is not valid evidence.
- Record the user's explicit YAML, direct Python, or direct Java API choice after
  the version pair. A combined recommended baseline is not evidence for any of
  these independent decisions.
- Confirm the workflow selected one matching host adapter before the first closed
  gate. Each gate must use its available native single-select tool or the generic
  numbered fallback; an unavailable native tool must not be retried in a loop.
  Reject open-ended version/API/language questions when the valid options were
  known, and reject a preselected recommended value without a user response.
- For a new YAML project, record the user's explicit Python or Java implementation
  choice after the YAML selection. An omitted YAML `type` default is not evidence
  of user intent. Record a Python/JDK choice only after the implementation language
  is known.
- When runtime Skills are requested for a new project, confirm that source and
  business implementation remain explicit TODOs rather than interview gates. For an
  existing configured source, preserve and validate its deployment topology.
- Require one resolved version set across the Maven Flink Agents modules and
  integrations or Python package, the selected Flink patch release, and the local
  Java/Python runtime.
- Use a matching target-version schema and API contract when available. Otherwise,
  use the [bundled YAML schema](../assets/yaml-schema.json) as the offline baseline
  and record that compatibility assumption.
- Record any API assumption that could not be confirmed. Do not generate a guessed
  version merely to make a dependency file look complete.

## 2. YAML Schema

Validate every generated or modified YAML file against the matching schema. If
`check-jsonschema` is already available:

```bash
check-jsonschema --schemafile <skill-dir>/assets/yaml-schema.json path/to/agent.yaml
```

Resolve `<skill-dir>` from the installed `SKILL.md` location. Do not assume the
current working directory is a Flink Agents source checkout. Replace the bundled
schema path with a target-version schema when one is available.

Otherwise use another real JSON Schema validator that accepts YAML. A YAML parser,
formatter, or linter only proves syntax and is not a schema substitute.

When working in the Flink Agents source checkout, the Python typed loader is a useful
additional check:

```bash
cd python
python - path/to/agent.yaml <<'PY'
from pathlib import Path
import sys

from flink_agents.api.yaml.loader import build_agents

agents, shared_resources, shared_actions = build_agents(Path(sys.argv[1]))
print("agents:", sorted(agents))
print("shared actions:", sorted(shared_actions))
PY
```

Use the repository's configured environment (`uv run --no-sync`, activated virtual
environment, or equivalent) instead of assuming system Python has the dependencies.

## 3. Static Reference Graph

Check every name edge explicitly:

- chat setup -> connection;
- chat setup -> Prompt;
- chat setup -> local or MCP Tool names;
- chat setup -> individual runtime Skill names;
- embedding setup -> embedding connection;
- vector store -> embedding setup;
- Action implementation -> Resource names passed to context lookups;
- emitted `ChatRequestEvent.model` -> chat setup;
- YAML shared Action string -> top-level Action;
- Python/Java `function` reference -> real callable/method.

Check uniqueness within each file. Across files loaded into the same execution
environment, multiple YAML loads accumulate and duplicate Agent or shared Resource
names fail. Shared Actions and their string references are file-scoped rather than
registered globally.

## 4. Action and Event Graph

For each YAML Action, classify it:

| Classification | Required evidence |
|---|---|
| Documented built-in behavior | Target-version docs/source identify its Event contract |
| Custom Python Action | Importable callable with `(Event, RunnerContext) -> None` |
| Custom Java Action | Public static method with Event and `RunnerContext` parameters |

Trace every path from `input` to `OutputEvent`. Check fan-out, correlation state,
custom event type strings, error paths, and whether any input can terminate without
an output. Verify event constructor arguments against the installed API rather than
memory.

For scaffolding, distinguish intended edges from implemented edges. Verify every
custom callable exists with the right signature, list each TODO business body, and
do not claim the path emits an event until that body is implemented. Reject invented
domain logic, service calls, prompts, runtime Skill instructions, and tests that
assert behavior the user did not specify.

## 5. Language Checks

Python:

- Require pinned `flink-agents` and `apache-flink` dependency input.
- Require a recorded user choice between a compatible existing Python environment
  and a project-local `.venv`, unless existing project metadata already made the
  environment unambiguous.
- Run the exact selected Python executable for every install, import, test, and job;
  do not require `.venv` when the user selected an existing environment.
- Run `pip check` with that executable.
- Import every function module from the same working directory/import path as the
  runner.
- Resolve nested qualnames.
- Run focused pytest tests for Actions, Tools, serialization, and graph branches.
- Check the runner's input/output types, key selector, and packaging of assets.
- When `package` Skills are selected, require an installable application package,
  include the full Skill tree as package data, install it into the selected Python
  environment, and resolve the configured resource from the installed package.
- Require `StreamExecutionEnvironment.get_execution_environment()` followed by
  `AgentsExecutionEnvironment.get_execution_environment(env)`, which is backed by
  `RemoteExecutionEnvironment`. Reject the no-argument Agents factory,
  `from_list`/`to_list`, and local Agents environment APIs.

Java:

- Require a complete Maven project with `flink-agents-api`, `flink-agents-plan`,
  `flink-agents-runtime`, and only the integration artifacts actually used.
- Require explicit Flink streaming, client, and Table bridge dependencies. Require
  the matching Table planner when the job uses Table API, and selected connector
  dependencies for non-built-in sources or sinks.
- Require `provided` scope on every Flink Agents and Flink dependency. Do not use a
  dist artifact or `flink-agents-ide-support` as an application dependency.
- Run `mvn dependency:tree` and inspect the resolved versions before compilation.
- Run the remote-style main class against a local MiniCluster in a forked JVM, for
  example through the generated `exec:exec` configuration; do not treat `exec:java`
  as equivalent evidence.
- Compile with the target Maven/Gradle command and configured JDK.
- Require `type: java` for Java YAML implementations.
- Match each Tool's `parameter_types` to the method parameters in order.
- Inspect the built JAR for YAML and, when `classpath` Skills are selected, the
  configured Skill resource tree.
- Require `AgentsExecutionEnvironment.getExecutionEnvironment(env)`, which is backed
  by `RemoteExecutionEnvironment`. Reject local Agents environment and list APIs.

Cross-language:

- Treat the selected API/application-code language and each Resource implementation
  language as independent dimensions.
- For chat-model connections/setups, embedding-model connections/setups, and vector
  stores, selecting a provider implementation is sufficient confirmation of its
  language. Do not require an additional application-wide cross-language question.
- Confirm the Resource type supports the bridge in the target-version API, not only
  in the matching YAML docs; direct Python and direct Java APIs support these bridges
  too.
- Confirm the generated descriptor uses the correct wrapper plus `java_clazz` or
  `pythonClazz` metadata, or the equivalent target-version factory.
- Confirm the selected language's integration artifact and bridge runtime are
  available to every TaskManager.
- Run the repository's cross-language tests or an equivalent focused test.
- Do not assume MCP, Skill source schemes, or arbitrary providers bridge languages.

## 6. Resource-specific Checks

All Resources:

- Each Resource was requested by the user or required by a confirmed reference;
  reject Resources inferred only from the application domain or a sample.
- Each descriptor-backed Resource uses the user-selected implementation class or
  documented alias. Its declaration contains every mandatory target-version
  argument as a typed/commented `TODO_REQUIRED_*` placeholder; reject additional
  configuration interviews or invented optional values.
- New-project Resource names are deterministic and all references use them
  consistently. Do not require user confirmation for an unambiguous generated name;
  require naming input only for collisions, external contracts, or an explicit
  naming convention. Existing names remain unchanged unless renaming was requested.
- Model names, endpoints, authentication, and optional provider settings are either
  explicitly supplied by the user or left as clear TODOs. Reject assumed provider
  values and reject scaffolding that blocks on collecting these values.
- No tracked file contains a supplied secret. Plaintext in a user-selected local
  YAML is valid when the file is ignored, the local entry point actually loads it,
  and output/reporting redacts the value. In a Git worktree, run `git check-ignore`
  and inspect `git status --short` before runtime validation.
- Do not treat `${ENV_VAR}` in YAML as secret injection unless the target
  loader/provider explicitly resolves it. Do not require programmatic Resource
  registration when the user selected a literal value in ignored local YAML.
- Resource references are resolved in dependency order, and the required
  integration artifact is selected only after the implementation is known.

Custom Tools, Actions, and domain clients:

- Preserve supplied names/signatures/types/descriptions. When they are absent,
  verify that capability-derived neutral signatures compile or import and their
  bodies fail explicitly; missing business contracts are TODOs, not failed gates.
- Reject guessed Flink REST endpoints, service protocols, domain transformations,
  and mock/test implementations unless the user explicitly requested and specified
  them.
- Reject scaffolding workflows that require the user to choose business identity
  fields, Flink/VVR/VVP platforms, log/metric interfaces, business authentication,
  or response schemas before files are generated.

Runtime Skills:

- Each Skill directory contains valid `SKILL.md` frontmatter.
- A user-fillable Skill scaffold contains only capability-derived metadata and a
  clear TODO;
  it must not contain invented domain instructions or commands.
- For a new project, the source is an explicit TODO and no coding-agent host Skill
  was inspected, copied, or offered for reuse. Do not require a path, URL, package,
  classpath, or distribution answer before scaffolding.
- Once the user fills it, the YAML field or direct `Skills` factory matches the source:
  bundled Python uses `package`/`from_package`, bundled Java uses
  `classpath`/`fromClasspath`, TaskManager-managed files use
  `paths`/`from_local_dir`/`fromLocalDir`, and remote ZIP distribution uses
  `urls`/`from_url`/`fromUrl`.
- A `package` source is Python-only and its resource is present in the installed
  package/wheel on every Python worker; a `classpath` source is Java-only and its
  resource is present in the application JAR or runtime classpath.
- For a filled source, every `paths` directory or ZIP is provisioned at a resolvable path on every
  TaskManager. A local MiniCluster check is not cluster-wide path evidence.
- For a filled source, every `urls` value is HTTP(S), points to a ZIP with Skill directories at its top
  level, and is reachable from every TaskManager. Prefer immutable, versioned URLs;
  do not claim cluster connectivity from a client-side download.
- Cross-language Skill sources use `paths` or `urls` and are tested across the
  confirmed bridge.
- If fields are intentionally combined, account for loader order `paths`, `urls`,
  `classpath`, `package`; reject duplicate Skill names rather than relying on
  last-wins replacement.
- Chat model `skills` entries match individual Skill names, not the Skills Resource
  name.
- `allowed_commands` contains only commands actually required by the enabled Skills.

MCP:

- Endpoint/auth configuration matches the language-specific docs.
- Discovered prompt/Tool names exist on the target server.
- Static checks do not claim MCP connectivity.

Vector stores and embedding models:

- Provider and language are supported together.
- Connection/setup/vector-store name references resolve.
- Collection/index, dimensions, and backend arguments match the provider.
- Tests distinguish schema/load checks from live backend queries.

File sources:

- Point streaming sources at input files/directories only.
- Exclude YAML, Skill, and other Resource assets from recursive input enumeration.

## 7. Runtime Evidence

Provide exact commands and name the user-confirmed configuration/credential
mechanism without reproducing a secret. Mention environment variables only when the
user selected them and the generated wiring resolves them. Label evidence precisely:

- `schema valid`: a JSON Schema validator completed successfully;
- `loads`: the Flink Agents loader built the definitions;
- `imports`: Python references resolved;
- `compiles`: Java build completed;
- `tests pass`: name the command and result;
- `MiniCluster deployment passed`: the remote-style bounded Flink job was submitted
  locally and finished; an empty typed source proves deployment only;
- `behavior smoke passed`: non-empty input invoked implemented business functions,
  produced the expected sink output, and the job terminated successfully;
- `integration verified`: the external model/MCP/vector service was actually reached.

Never upgrade `compiles` to MiniCluster deployment, deployment-only evidence to
behavior evidence, or `schema valid` to `provider configuration works`. State
skipped checks and the exact missing implementation, service, credential,
dependency, or environment.

## Final Report Template

```text
Changed:
- <files and behavior>

Selected versions:
- Flink Agents <user-confirmed version>; Flink <user-confirmed version>

Selected API:
- <user-confirmed YAML, direct Python, or direct Java>

Implementation language:
- <user-confirmed custom Action/Tool and entry-point language>

Python environment, when required:
- <selected existing executable or project-local .venv executable>

Selected Resource implementations:
- <name: implementation class/alias and implementation language; bridge form and mandatory TODO fields generated>

Runtime Skill source:
- <preserved existing source, user-fillable TODO, or not used>
- <supported source forms and deployment requirements for the user to fill>

User must provide:
- <custom Action/Tool/Skill business behavior>
- <business input and result contracts>
- <platform client, authentication, query, and response mapping>
- <secrets, endpoints, and external data>

Verified:
- <command>: <result>

Not run:
- <check>: <reason>
```
