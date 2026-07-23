# Python Patterns

## Contents

- [Match the Installed API](#match-the-installed-api)
- [Cross-language Descriptor Resources](#cross-language-descriptor-resources)
- [YAML-referenced Implementations](#yaml-referenced-implementations)
- [Function Tools](#function-tools)
- [Resource Access and Events](#resource-access-and-events)
- [Runtime Skill Packaging](#runtime-skill-packaging)
- [Programmatic ReActAgent](#programmatic-reactagent)
- [Python Checks](#python-checks)

## Match the Installed API

Inspect the target application's dependency version, imports, tests, and existing
Agents code. Confirm uncertain constructors and provider parameters in the matching
docs or source. Do not pin guessed Flink, Flink Agents, provider, or model versions.
If the target has no dependency metadata, use the supported choices in
`local-development.md` and obtain the user's selection before writing dependency or
source files.

The patterns below are the bundled offline baseline. When a target environment is
available, inspect the installed `flink_agents` package and its metadata for changed
signatures or provider integrations. A source checkout is optional.

## Cross-language Descriptor Resources

A direct Python Agent may use Java implementations of chat-model connections/setups,
embedding-model connections/setups, and vector stores. Do not filter Resource
provider choices to Python implementations merely because the Agent, custom Actions,
or entry point are Python.

After the user selects a Java implementation, build the target-version documented
descriptor with the corresponding Python-side Java wrapper and `java_clazz` set to
the selected Java implementation FQN. Add the matching Java integration artifact to
the job/runtime classpath as well as the Python bridge dependencies. Do not translate
the provider into a Python implementation or ask for a separate cross-language
confirmation. This bridge does not establish support for arbitrary Resource types;
verify the wrapper map in the target version.

## YAML-referenced Implementations

Custom Actions use the fixed `(Event, RunnerContext) -> None` contract. When the
user has not supplied their business behavior, generate importable skeletons rather
than inferring event transformations or domain policy:

```python
from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext


def process_input(event: Event, ctx: RunnerContext) -> None:
    """TODO: Map an InputEvent to the application's first event."""
    raise NotImplementedError(
        "Implement the application-specific input Action"
    )


def process_chat_response(event: Event, ctx: RunnerContext) -> None:
    """TODO: Validate the model response and emit the application output."""
    raise NotImplementedError(
        "Implement the application-specific response Action"
    )
```

Implement an event transformation only when the user explicitly requests it and its
input/output behavior is known. Then adapt each event constructor only after reading
its signature in the target version. For custom event types, emit
`Event(type="...", attributes={...})` and use the same type string in YAML
`trigger_conditions`.

Treat each constructor keyword and property as a versioned contract. Import or
compile the generated module against the target environment before describing it as
runnable; otherwise label the example as pseudocode and identify the unresolved
symbol.

YAML references these as:

```yaml
function: app.actions:process_input
function: app.actions:process_chat_response
```

The module must be importable from the runner's environment. Static methods use
`module:Class.method`. Generate the module and callable before claiming the YAML
loads.

## Function Tools

Use typed parameters and a useful docstring so the framework can derive the Tool
schema. Keep framework-owned values out of the model schema with documented injected
arguments rather than hidden globals. If the user stated a capability but did not
supply its business contract, do not stop to interview for domain identity fields,
platform variants, endpoints, or authentication. Generate a neutral skeleton:

```python
def query_logs(request: str) -> str:
    """TODO: Define the diagnostic request and result contracts."""
    raise NotImplementedError("Connect the user-selected log backend")
```

`NotImplementedError` is the default when the user has not provided the business
integration. Do not invent an HTTP client, endpoint, query, authentication scheme,
response shape, or fallback merely to make the Tool look complete. `request: str`
and `str` are placeholder boundary types, not a recommended domain API. Keep all
Flink Agents wiring around the signature complete and list the unresolved contract
for the user after scaffolding.

## Resource Access and Events

Use `ctx.get_resource(name, ResourceType.<TYPE>)` with an exact declared name. For
vector retrieval, prefer the documented built-in flow when it fits:

1. Send `ContextRetrievalRequestEvent` using the target version's constructor.
2. Handle `ContextRetrievalResponseEvent` in a custom Action.
3. Read its documents and send the next event.

For direct queries, construct `VectorStoreQuery` and call the retrieved vector
store. Do not mix direct and event-driven retrieval accidentally.

## Runtime Skill Packaging

Runtime Skill behavior and source configuration are user-owned. Preserve an existing
`package`, `paths`, or `urls` source. For a new application, generate a minimal
`SKILL.md` TODO scaffold and list `Skills.from_package(...)`,
`Skills.from_local_dir(...)`, and `Skills.from_url(...)` in a factory TODO; do not
ask the user to select one and do not package or load a source speculatively.

The factory helper must remain importable and fail explicitly until configured.
Never inspect Python environment, Codex, Claude Code, Qoder, or other host Skill
directories to find reusable business content. A host `flink-diag` Skill is not a
Flink runtime Skill dependency. See `yaml-patterns.md#runtime-skills` for the fields
the user can fill later.

## Programmatic ReActAgent

Do not choose `ReActAgent` merely for a reasoning/tool loop; Workflow Agents already
provide that loop through built-in Actions. When the decision rules do select the
programmatic abstraction, construct it from the user-confirmed chat-model descriptor,
optional Prompt, and optional Pydantic or `RowTypeInfo` output schema. This is shape
pseudocode; resolve each value after the Resource interview:

```python
from flink_agents.api.agents.react_agent import ReActAgent

agent = ReActAgent(
    chat_model=configured_chat_model_descriptor,
    prompt=confirmed_prompt_or_none,
    output_schema=confirmed_output_schema_or_none,
)
```

Build `configured_chat_model_descriptor` only after the user selects its
implementation. Generate its verified mandatory arguments as TODOs rather than
asking for values. Register every Resource it references under the exact name before
`.apply(agent)`; do not introduce a provider, model, Prompt, or Tool solely to
complete the example.

## Python Checks

- Parse and build YAML with the Flink Agents loader.
- Import every left-side module in `function` references and resolve each qualname.
- Inspect or test each Action signature and emitted event constructor.
- Run focused pytest tests with the target repository's environment.
- Set the repository-required `PYTHONPATH` before Python-facing or cross-language
  tests when working in the Flink Agents source checkout.
- Exercise provider integrations only when their services and credentials are
  available; label skipped integration checks.
- For a Java chat-model, embedding-model, or vector-store implementation, verify the
  Python descriptor wrapper, `java_clazz`, selected Java integration JAR, and Java
  resource adapter path. Do not reject it because the application code is Python.
- Only after the user fills a runtime Skill source: for bundled Skills, inspect the
  built wheel or installed package and load the configured package-data resource;
  for `paths` or `urls`, verify the corresponding deployment preconditions without
  treating local access as cluster proof.
- Follow `local-development.md` to ask whether to reuse a compatible existing
  Python environment or create `.venv`, install dependencies only after that choice,
  and submit the bounded remote-style Flink job to a local MiniCluster. Always pass a
  `StreamExecutionEnvironment` to `AgentsExecutionEnvironment.get_execution_environment`;
  never use the no-argument factory, a local Agents environment, `from_list`, or
  `to_list`.
