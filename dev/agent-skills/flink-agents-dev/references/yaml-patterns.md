# YAML Patterns

## Contents

- [Contract First](#contract-first)
- [Canonical Workflow Shape](#canonical-workflow-shape)
- [Section Rules](#section-rules)
- [Language Selection](#language-selection)
- [Name-resolution Pass](#name-resolution-pass)

## Contract First

Use the [bundled YAML schema](../assets/yaml-schema.json) as the offline contract
before generating YAML. If the target application provides a schema for its pinned
Flink Agents version, use that matching schema instead. The schema defines structure;
target-version provider metadata defines the additional arguments forwarded by
Resource descriptors.

The valid top-level sections are:

```text
agents, actions, chat_model_connections, chat_model_setups,
embedding_model_connections, embedding_model_setups, prompts, tools,
skills, vector_stores, mcp_servers
```

Top-level Resources and Actions are shared. The same sections nested under an
`agents[]` entry belong to that Agent. There is no `resources:` wrapper and no Agent
`type` field.

## Canonical Workflow Shape

This example deliberately shows only the Action structure. It contains no model,
Prompt, Tool, Skill, MCP server, or vector store because those Resources must come
from the staged user interview. Generate `app.actions` with matching skeletons; do
not infer what either Action emits.

```yaml
agents:
  - name: application_agent

    actions:
      - name: process_input
        function: app.actions:process_input
        trigger_conditions: [input]
        type: python
```

Add downstream Actions only after their trigger events are confirmed. A skeleton's
existence proves only that the YAML reference resolves; it does not prove an event
is emitted.

## Section Rules

### Actions

An inline Action requires `name`, non-empty `trigger_conditions`, and a valid
`function` for custom behavior. `config` is optional. An Agent may also reference a
top-level shared Action by a bare string.

```yaml
actions:
  - name: shared_input
    function: app.actions:shared_input
    trigger_conditions: [input]

agents:
  - name: first_agent
    actions: [shared_input]
```

Event aliases include `input`, `output`, `chat_request`, `chat_response`,
`tool_request`, `tool_response`, `context_retrieval_request`, and
`context_retrieval_response`. Use the exact `EVENT_TYPE` string for custom events.

### Function References

Use exactly one colon:

```yaml
# Python top-level function
function: app.actions:process_input

# Python static method
function: app.actions:ReviewActions.process_input

# Java static method
function: com.example.agent.Actions:processInput

# Java nested class
function: com.example.Outer$Actions:processInput
```

The left side is a Python module or Java class FQN; the right side is the qualname.
Do not use a single dotted path in place of the colon.

### Prompts and Tools

A Prompt has exactly one of `text` or `messages`. Template substitution uses
`{name}`. A Tool needs a callable `function`; Java Tools also need ordered
`parameter_types`, while Python Tools must not declare them.

```yaml
tools:
  - name: add
    type: java
    function: com.example.Tools:add
    parameter_types: [java.lang.Integer, java.lang.Integer]
```

The example shows the YAML shape, not a required business interview. If the user
requested a Tool capability but did not supply its contract, generate a capability-
derived name and a neutral one-string-input/one-string-output function skeleton.
For Java, set `parameter_types: [java.lang.String]`; for Python, omit
`parameter_types`. Put the unknown request fields, platform client, authentication,
query, and response mapping in TODOs. Do not ask the user to choose those details
before generating the project.

### Descriptor-backed Resources

Chat-model and embedding connections/setups, vector stores, and MCP servers require
`name` and `clazz`. Their remaining fields are forwarded to the provider. Use only
arguments documented for the selected provider and language. Resolve one Resource
at a time: ask for `clazz` first, inspect that target-version implementation, then
generate its required arguments as `TODO_REQUIRED_*` fields. Do not ask the user for
those values, and do not copy a model, endpoint, or authentication setting from this
or another example. If target-version metadata does not reveal the full constructor
contract, add `TODO_VERIFY_REQUIRED_PROVIDER_ARGUMENTS` and report the gap.

For chat-model connections/setups, embedding-model connections/setups, and vector
stores, the descriptor's `type` is independent of the custom Action/Tool and Flink
entry-point language. Offer aliases from both Python and Java buckets when the
target-version loader exposes the corresponding wrapper. A Python-loaded YAML may
use `type: java`; a Java-loaded YAML may use `type: python`. Set `type` explicitly
from the selected Resource implementation and add its integration/runtime artifact.
Do not apply this cross-language rule to MCP servers, Skills, Tools, Prompts, or
other Resource types without verified bridge support.

The required `name` is an internal YAML identifier, not automatically a user
decision. For one Resource of a role, generate the defaults documented in
`application-patterns.md`, including `chat_model_connection` and `chat_model`, and
write the matching Setup `connection` reference directly. Preserve existing names.
Ask for naming input only to resolve multiple-resource ambiguity, satisfy an exact
external reference, or follow a user-requested convention.

The bundled YAML loader has no general environment-variable interpolation for
descriptor arguments. Do not turn an unknown credential into `${API_KEY}` or claim
that it will be resolved. A literal provider argument in YAML is different from
interpolation: when the user chooses plaintext for local testing, write the literal
value to an untracked local YAML using the provider's documented field, such as
`api_key`, and load that YAML directly. Do not force programmatic `addResource`
registration merely because interpolation is unavailable.

Keep the local YAML outside tracked application resources where practical, add its
exact path or pattern to `.gitignore`, verify it is ignored, and ensure the local
runner or command loads that file. Warn once that the file and any locally built
artifact containing it hold plaintext. Never echo or reproduce the value in logs,
commands, diffs shown to the user, or the final report. Programmatic registration
and provider-supported secret stores remain valid alternatives, not mandatory
replacements for the user's local-testing choice.

MCP prompts and Tools are discovered dynamically and referenced by each prompt or
Tool's advertised name, just like local Prompts and Tools. The MCP server Resource
name is separate. Verify discovered names and collisions against the MCP server;
the static schema cannot do so.

### Runtime Skills

At least one source list must be non-empty. YAML describes the selected source; it
does not decide how Skills are deployed:

| YAML field | Runtime scheme | Supported loader | Required deployment condition |
|---|---|---|---|
| `paths` | `local` | Python and Java | Each TaskManager can resolve the directory or ZIP path |
| `urls` | `url` | Python and Java | Each TaskManager can reach the HTTP(S) ZIP URL |
| `classpath` | `classpath` | Java only | Skill resources are present on the runtime classpath, normally in the application JAR |
| `package` | `package` | Python only | Skill resources are package data in an installed Python package/wheel |

Preserve an existing explicit source unless the user requests a change. For a new
application, do not ask for source paths, URLs, packages, classpath locations, or
distribution. Generate a visibly unresolved declaration that lists the valid forms:

```yaml
skills:
  - name: runtime_skills
    # TODO(required): replace this placeholder with the chosen source.
    # Supported forms: paths, urls, Python package, or Java classpath.
    paths: [TODO_REQUIRED_SKILL_SOURCE]
```

`paths` is a schema-shaped placeholder in this scaffold, not a selected deployment
mode. The generated application is not runtime-ready until the user replaces it.
Do not package, mount, download, or validate a source that the user has not supplied.

Do not choose `package` merely because the implementation is Python or `classpath`
merely because it is Java. Language only constrains the valid bundled scheme. Use
`paths` or `urls` for a supported cross-language source. A relative `paths` entry is
resolved in the TaskManager runtime, not guaranteed to be the submitting client's
working directory; success in a local MiniCluster does not prove cluster-wide path
availability.

Multiple source fields can coexist when the user explicitly requests composition.
The loaders append sources in `paths`, `urls`, `classpath`, `package` order, and a
later source replaces an earlier registration with the same Skill frontmatter name.
Avoid duplicate names and implicit fallback behavior. An unsupported scheme fails
at load time; a runtime does not skip it as a fallback. Prefer immutable, versioned
URLs; the YAML schema does not provide a checksum field, so do not invent one.

Declaring a Skills Resource only makes Skills available. To activate one after its
business content and source are filled, add its `SKILL.md` name to a chat model
setup's `skills` list. Add `allowed_commands` only when the user later supplies
Skill behavior requiring shell operations; `load_skill` and `bash` are added
automatically.

Runtime Skill instructions are business content. Derive minimal metadata from the
stated capability and leave a focused TODO body. Do not ask implementation questions
or invent runbooks, diagnostic rules, tool sequences, shell commands, or safety
policy. Never inspect or reuse a coding-agent host Skill such as a locally installed
`flink-diag`; host Skills and Flink runtime Skills have different owners and
deployment contracts.

```markdown
---
name: capability-derived-skill-name
description: TODO: Refine the runtime Skill purpose and trigger.
---

# Capability-derived Skill Name

TODO: Define the domain workflow, evidence rules, and permitted tools.
```

## Language Selection

Missing `type` means `python` for both loaders. Mark each Action, Tool, and
descriptor-backed Resource with its own implementation language rather than copying
one application-wide value. The bundled snapshot supports bidirectional bridging
for chat-model connections/setups, embedding-model connections/setups, and vector
stores. Consult the target version's YAML docs and plan compiler before assuming
that another Resource type bridges languages.

The schema default is not a product decision. For a new application, first confirm
the Flink Agents/Flink pair and then the YAML API choice. Only then ask the user to
choose Python or Java before generating functions, Resources, dependencies, or the
Flink entry point. Present Python and Java as native single-select options or a
numbered fallback, not an open-ended language question. Treat them as equal peer
options: use parallel descriptions, label neither one `(Recommended)`, and preselect
neither one. Apply the user's choice to custom Action/Tool implementations and the
Flink entry point, and do not mention a Python/JDK version before it. Preserve
explicit language choices in existing YAML. Do not require a separate cross-language
confirmation when the user selects a bridge-supported Resource implementation; that
Resource's selector already records the choice.

In the current source, Java `AgentPlan` rejects MCP servers added through
`Agent.addResource`; Java `YamlLoader` represents YAML `mcp_servers` through that
path. Therefore, do not claim that a Java-loaded YAML application with
`mcp_servers` is runnable, regardless of `type`. Use the documented Java
`@MCPServer` programmatic definition for that application, or verify that the
target version has removed this restriction. Do not invent a YAML/MCP bridge or
adapter.

## Name-resolution Pass

Before loading, build and check this graph:

- setup `connection` -> declared connection name;
- setup `prompt` -> declared Prompt name;
- setup `tools[]` -> local Tool or MCP-discovered Tool name;
- setup `skills[]` -> individual runtime Skill frontmatter name;
- embedding setup `connection` -> embedding connection name;
- vector-store `embedding_model` -> embedding setup name;
- `ChatRequestEvent.model` in implementation code -> chat setup name;
- Action and Tool `function` -> importable Python callable or Java static method.

Schema validation cannot prove every dynamic name or provider argument. Run the
loader/build checks from `verification.md` after schema validation.
