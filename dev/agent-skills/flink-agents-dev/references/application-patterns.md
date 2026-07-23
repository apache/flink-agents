# Application Patterns

## Contents

- [Portable Baseline](#portable-baseline)
- [Run a Gated Interview](#run-a-gated-interview)
- [Present Closed Choices Portably](#present-closed-choices-portably)
- [Choose the API and Agent Form](#choose-the-api-and-agent-form)
- [Choose the Implementation Language](#choose-the-implementation-language)
- [Resource Inventory](#resource-inventory)
- [Resolve Resources One at a Time](#resolve-resources-one-at-a-time)
- [Scaffold Runtime Skills](#scaffold-runtime-skills)
- [Design the Action Graph](#design-the-action-graph)
- [Built-in Action Boundaries](#built-in-action-boundaries)
- [Separate Framework and Business Code](#separate-framework-and-business-code)
- [Minimal Layouts](#minimal-layouts)

## Portable Baseline

Do not require a Flink Agents source checkout. This installed skill contains:

- `references/application-patterns.md`: Agent selection, Resources, Actions, and graph design;
- `references/yaml-patterns.md`: complete YAML structure and naming rules;
- `references/python-patterns.md`: Python Action, Tool, event, and runner contracts;
- `references/java-patterns.md`: Java function, Resource, classpath, and runner contracts;
- `references/local-development.md`: Maven/venv generation and local Flink execution;
- `references/verification.md`: schema, reference, compile, and runtime checks;
- [`assets/yaml-schema.json`](../assets/yaml-schema.json): a machine-readable offline YAML contract.

These files are a coherent snapshot and are sufficient for the default workflow.
When the target application pins another Flink Agents version, inspect that installed
Python package, Java dependency/source JAR, released docs, or source tag for changed
constructors and provider arguments. Prefer the target-version contract over the
snapshot and report the mismatch; never require cloning the repository as a setup
step.

## Run a Gated Interview

An empty workspace provides no evidence for language, API, provider, credentials,
Skill source, or business integrations. Do not answer that uncertainty with a
combined recommended baseline. Complete and wait at each gate:

| Gate | Ask now | Do not ask or assume yet |
|---|---|---|
| Versions | Flink Agents, then compatible Flink | API, Python/JDK, Resources, business backend |
| API | YAML, direct Python, or direct Java | YAML implementation language, providers, credentials |
| Language/runtime | Python or Java as equal choices only for YAML; then compatible interpreter/JDK | Resource implementations |
| Resources | One descriptor-backed framework implementation; generate its required arguments as TODOs | Another integration or any custom business body |
| Python environment | When Python first becomes required: compatible existing environment or project `.venv` | Dependency installation before the choice |
| Application | Action graph and custom signatures | Unspecified domain logic or test doubles |

Skip a gate only when the user already answered it or an existing project declares
it unambiguously. A recommended option must be presented inside its own gate and
must not silently answer later gates.

## Present Closed Choices Portably

For version, API, YAML implementation language, runtime, and framework Resource
implementation gates, the valid set is known. Use the single host adapter selected
by `SKILL.md`.
Platform-specific tool names belong only in `references/platforms/`; they must not
leak into the generated application or become a prerequisite for using the core
skill. Treat the current session's actual tool contract as authoritative instead of
assuming a tool exists because of the host product name.

Follow the selected adapter's mode prerequisite before deciding that its native
tool is unavailable. For Codex, pause before the first gate and ask the user to
enter Plan mode with `/plan` or `Shift+Tab`; do not render a text menu while Codex
is still in Default mode. When the matching native tool remains unavailable after
the adapter prerequisite or rejects the call, use the generic portable fallback
without repeatedly retrying the unavailable tool:

```text
Select the API:
1. YAML API (Recommended)
2. Direct Python API
3. Direct Java API

Reply with 1, 2, or 3.
```

Keep one decision gate per selector. When that gate has a recommendation, put the
recommended option first and label it, but do not mark it selected or proceed on
timeout/silence. The YAML implementation-language gate has no recommended option.
For a target-version list of Resource aliases/providers, offer every implementation
verified as compatible with the selected API either natively or through a supported
cross-language bridge. Do not filter chat-model, embedding-model, or vector-store
implementations by the application-code language. Include a custom class option only
when the framework supports one. Ask free-form text only for genuinely open values
such as a custom class name, model identifier, endpoint, or user-defined Tool
signature. Generate internal Resource names unless the user requested a naming
convention or multiple Resources cannot be distinguished from confirmed context.

## Choose the API and Agent Form

After the version pair is confirmed, ask the user to choose:

| API choice | Consequence |
|---|---|
| YAML API | Declarative Workflow Agent; ask Python or Java implementation language next |
| Direct Python API | Python implementation and PyFlink entry point |
| Direct Java API | Java implementation and Java Flink entry point |

YAML is the recommended option for a new Workflow Agent because the schema can
validate the wiring, but do not select it without the user's answer. Direct API does
not imply `ReActAgent`; both Python and Java can define Workflow Agents
programmatically.

Use a Workflow Agent when the user needs an explicit event graph, deterministic
stages, custom state transitions, branching, joins, or direct Resource access.

Workflow Agents can also provide a model reasoning/tool loop using the built-in
chat and tool-call Actions. Add the built-in context-retrieval flow when reasoning
needs retrieved context. Do not switch to `ReActAgent` merely because the
application uses model reasoning, Tools, RAG, MCP Tools, or runtime Skills.

Use the programmatic `ReActAgent` when the user explicitly requests that
opinionated abstraction, the existing application already uses it, or a required
ReAct-specific surface is unavailable through YAML. The YAML schema has no
`type: react` discriminator. Do not simulate one with an unsupported field.

Preserve an existing definition style unless the user requests conversion or the
current style blocks the requested capability.

## Choose the Implementation Language

YAML defines the Agent graph but does not make custom code language-neutral. Only
after the user selects YAML, ask them to choose one implementation language before
creating files:

| Choice | Generated contracts |
|---|---|
| Python | Python module references for custom Actions/Tools, a user-selected Python environment, and a PyFlink entry point |
| Java | Java class/method references for custom Actions/Tools, Tool `parameter_types`, Maven, and a Java Flink entry point |

Present these as two equal peer options. Use parallel factual descriptions, do not
append `(Recommended)` to either label, do not preselect either language, and do not
describe list order as a preference. The user must make the choice even when one
language happens to be installed locally.

Do not mention or choose a Python/JDK version before this language choice. Do not
choose Python because the coding agent runs in Python or because missing YAML `type`
defaults to Python. Do not choose Java merely because Flink runs on the JVM. The
selected language constrains application source layout, runtime compatibility, and
local tooling, but it does not constrain bridge-supported chat-model,
embedding-model, or vector-store implementations. Resolve the compatible interpreter
or JDK immediately after the application-code language is confirmed.

The Python environment is a separate conditional gate. It also applies when the
application code is Java but a selected Resource implementation or verification
step requires Python. Detect compatible local Python executables and environments,
then ask whether to reuse a specific detected environment or create a project-local
`.venv`. Do not create an environment or install dependencies before the user
chooses. Preserve an existing project environment when project metadata declares it
unambiguously.

For an existing application, detect and preserve its language from `pom.xml`, Python
dependency metadata, source layout, function references, and explicit YAML `type`
fields on custom Actions and Tools. If those application-code signals conflict,
surface the conflict and confirm the boundary with the user. A chat-model,
embedding-model, or vector-store implementation in the other language is an
independent bridged Resource choice, not an application-language conflict and not a
reason for another confirmation gate.

## Resource Inventory

After the API and language gates, build a name table before writing Actions or YAML.
Include only Resources required by the user's stated design; do not add a model,
Skills, MCP, vector store, or Tool because it appeared in an example:

| Resource | Typical references |
|---|---|
| `chat_model_connections` | `chat_model_setups[].connection` |
| `chat_model_setups` | `ChatRequestEvent.model`; runner/Agent configuration |
| `prompts` | `chat_model_setups[].prompt` |
| `tools` | `chat_model_setups[].tools`; direct `ToolRequestEvent` |
| `skills` | available Skill sources; individual names in chat model `skills` |
| `embedding_model_connections` | `embedding_model_setups[].connection` |
| `embedding_model_setups` | vector-store `embedding_model` argument |
| `vector_stores` | Action lookup; `ContextRetrievalRequestEvent` |
| `mcp_servers` | dynamically discovered MCP prompts and Tools |

Treat each name as an API. Keep spelling and case identical at declaration and
every reference. Separate a Skills Resource name from the individual Skill names
discovered from its `SKILL.md` files.

For a new application, naming is coding-agent work rather than a user decision.
Generate deterministic `snake_case` identifiers and wire every reference to them.
Use these defaults when there is one Resource of that role:

| Resource | Default name |
|---|---|
| Chat-model connection | `chat_model_connection` |
| Chat-model setup | `chat_model` |
| Embedding-model connection | `embedding_model_connection` |
| Embedding-model setup | `embedding_model` |
| Prompt | `<purpose>_prompt` |
| Tool | snake_case of the confirmed function or capability |
| Skills Resource | `runtime_skills` |
| Vector store | `vector_store` |
| MCP server | `mcp_server` |

When there are multiple Resources of one role, derive a meaningful provider or
purpose stem from already confirmed information. Ask the user for naming input only
when that information cannot disambiguate them, an existing external reference
requires an exact name, or the user explicitly requested a naming convention.
Preserve existing names during modifications. Report generated names, but do not
pause for confirmation.

## Resolve Resources One at a Time

Do not ask the user to approve a complete Resource stack. Select one Resource from
the inventory, resolve it fully, and wait before moving to the next one. Follow its
dependency edges: configure a connection before the setup that names it, and
configure the Prompt, Tools, or Skills before finalizing a setup that references
them.

For each Resource, generate its name and references. Ask only which documented
framework implementation/alias to use; inspect that target-version implementation
and generate all mandatory configuration fields as TODOs. Function Tools, Prompt
content, runtime Skill instructions, and Skill source configuration are scaffolds,
not provider-integration interviews:

| Resource | Generate | Ask for |
|---|---|---|
| Chat/embedding connection | Stable name plus every mandatory provider field marked `TODO_REQUIRED_*` | Documented `clazz`/alias only |
| Chat/embedding setup | Stable name, connection reference, and mandatory setup fields marked `TODO_REQUIRED_*` | Documented `clazz`/alias only |
| Prompt | Purpose-derived name and TODO scaffold | Nothing unless the user already supplied content or explicitly requests prompt implementation |
| Function Tool | Capability-derived name, minimal neutral signature, and failing body | Nothing unless the user already supplied a signature or explicitly requests implementation |
| Skills | `runtime_skills`, minimal Skill metadata, TODO instructions, and source-configuration TODO | Nothing; do not interview for source paths or business instructions |
| Vector store | Stable name, embedding-setup reference, and mandatory backend fields marked `TODO_REQUIRED_*` | Documented implementation only |
| MCP server | Stable name and mandatory transport/authentication fields marked `TODO_REQUIRED_*` | Documented implementation only |

Choosing a Resource implementation determines which integration artifact and
arguments are valid. For chat-model connections/setups, embedding-model
connections/setups, and vector stores, build the candidate set from both Python and
Java implementations supported by the target version. Label otherwise ambiguous
choices with their implementation language, for example `Ollama (Python)` and
`Ollama (Java)`. Selecting one candidate is sufficient confirmation of the Resource
language; do not ask a separate cross-language question. Read the matching
target-version docs or installed API after the user names the implementation; do not
propose OpenAI, Ollama, a model name, or a provider alias from the application
domain.

Generate the corresponding bridge form instead of rewriting the provider into the
application language. In YAML, set each descriptor's `type` from that Resource's
implementation language. In direct Python, use the documented Java wrapper and
`java_clazz` metadata for a Java implementation. In direct Java, use the documented
Python wrapper and `pythonClazz` metadata for a Python implementation. Add the
selected implementation artifact and bridge runtime requirements to the generated
project. Keep connection and setup implementations compatible with one another.

Do not ask for values after the implementation is selected. Put every verified
mandatory key in the generated declaration and use an unmistakable placeholder,
for example `model: TODO_REQUIRED_MODEL` or `api_key: TODO_REQUIRED_API_KEY`.
Include concise comments that identify required types or constraints when the
target-version API exposes them. Do not invent optional values. If the required
arguments cannot be verified, add `TODO_VERIFY_REQUIRED_PROVIDER_ARGUMENTS` and
report the documentation gap instead of asking the user to design the provider.

An API-key placeholder is not a secret and may remain in a tracked template. If the
user explicitly supplies a real value or asks to keep plaintext in local YAML, put
the value only in an ignored local file and redact output. The lack of general
`${ENV_VAR}` interpolation does not justify another interview; simply document it
and leave the supported credential field for the user to fill.

A custom Tool, Action, or domain client is business code. When its contract is not
already supplied, derive a name from the user's stated capability and generate a
minimal framework-compatible skeleton using opaque inputs and outputs. Do not ask
the user to choose structured identity fields, Flink/VVR/VVP platform variants,
service endpoints, authentication, log/metric APIs, or response schemas merely to
finish scaffolding. Record each unknown as a TODO and consolidate it in the final
`User must provide` list. Do not generate a fake or mock implementation unless the
user explicitly requests that behavior.

## Scaffold Runtime Skills

Runtime Skill instructions and deployment are user-owned. A coding-agent Skill
installed in Codex, Claude Code, Qoder, Gemini CLI, or another host is not a Flink
runtime Skill artifact. Never inspect host Skill directories, ask whether to reuse a
local Skill such as `flink-diag`, or copy its content into the generated application.

Preserve an existing YAML source field or `Skills` factory. For a new application,
do not ask how Skills will be loaded. Generate:

- a minimal runtime `SKILL.md` with capability-derived metadata and a TODO body;
- a `runtime_skills` Resource/source scaffold that clearly requires user input;
- a final TODO listing the supported source forms and deployment requirements.

The supported forms are reference information for the user's later edit, not
selection options in the scaffolding interview:

| Source form | YAML source | Direct API | Deployment contract |
|---|---|---|---|
| Bundle with the application | Python `package`; Java `classpath` | Python `Skills.from_package`; Java `Skills.fromClasspath` | Version the Skills with the Python package/wheel or application JAR and install/deploy that artifact on the runtime |
| TaskManager-local path | `paths` | Python `Skills.from_local_dir`; Java `Skills.fromLocalDir` | Every TaskManager must resolve the same directory or ZIP path with the same contents |
| Versioned HTTP(S) ZIP | `urls` | Python `Skills.from_url`; Java `Skills.fromUrl` | Every TaskManager must reach the URL; the ZIP top level contains Skill subdirectories |

For a YAML scaffold, use a visibly unresolved schema-shaped template such as:

```yaml
skills:
  - name: runtime_skills
    # TODO(required): replace this placeholder with paths, urls, package, or classpath.
    paths: [TODO_REQUIRED_SKILL_SOURCE]
```

The placeholder keeps the required declaration visible; it is not a selected
distribution mode and is not runnable. For a direct API scaffold, generate a
compilable factory/helper that lists the valid factories and throws
`NotImplementedError` or `UnsupportedOperationException` until the user chooses one.
Do not package, mount, download, or activate the runtime Skill on the user's behalf
unless they later provide the source configuration or explicitly ask for it.

Once the user fills the source, multiple fields may coexist only when explicitly
intended. The implementation language removes invalid choices (`package` is
Python-only and `classpath` is Java-only), while `paths` and `urls` can bridge
languages. Validate loader order and deployment constraints at that time.
YAML loaders append them in `paths`, `urls`, `classpath`, `package` order. Runtime
registration is last-wins for duplicate Skill frontmatter names, so avoid duplicate
names instead of relying on an implicit override or fallback chain. Prefer immutable,
versioned URLs because the YAML contract has no checksum field. A path that works in
a local MiniCluster proves only single-machine availability, not cluster-wide
TaskManager visibility.

## Design the Action Graph

Write the framework graph before code. This is an internal design checklist, not a
business requirements interview. Use explicit user requirements when available;
otherwise generate stable stage/event names and TODO-marked opaque payloads without
asking blocking questions:

| Item | Record |
|---|---|
| Trigger | A documented built-in alias or generated custom event type |
| Input | Confirmed attributes, or an opaque payload with a TODO for the domain schema |
| State | Only explicitly requested memory; otherwise no invented domain state |
| Resources | Exact generated or preserved Resource names |
| Emission | Framework stage transition; unresolved business fields remain TODOs |
| Failure | Explicit failure for skeleton code; domain recovery remains a TODO |

Multiple Actions may listen to the same event. Account for fan-out, duplicate
outputs, joins, and correlation state explicitly. Normally emit `OutputEvent` only
after the final response; it bypasses further Action routing and reaches downstream
immediately.

## Built-in Action Boundaries

The official docs currently identify these built-in behaviors:

| Built-in Action | Listens for | Emits |
|---|---|---|
| `chat_model_action` | `ChatRequestEvent`, `ToolResponseEvent` | `ChatResponseEvent` or `ToolRequestEvent` |
| `tool_call_action` | `ToolRequestEvent` | `ToolResponseEvent` |
| `context_retrieval_action` | `ContextRetrievalRequestEvent` | `ContextRetrievalResponseEvent` |

These Actions are added automatically when an AgentPlan is compiled. Do not invent
YAML declarations or implementation functions for them. Invoke them through their
documented Events and configured Resources.

The reasoning/tool loop is:

```text
ChatRequestEvent -> chat_model_action -> ToolRequestEvent
ToolRequestEvent -> tool_call_action -> ToolResponseEvent
ToolResponseEvent -> chat_model_action -> ChatResponseEvent or another ToolRequestEvent
```

Start the loop from a custom Action by emitting `ChatRequestEvent`. Handle the final
`ChatResponseEvent` in another custom Action, usually by emitting `OutputEvent`.
Context retrieval can run before or between reasoning stages by emitting
`ContextRetrievalRequestEvent` and handling `ContextRetrievalResponseEvent`.

Every Action listed in YAML is otherwise a custom Action and needs a concrete,
resolvable function signature. If a purported built-in is not documented for the
target version, treat it as custom until source or tests prove otherwise.

## Separate Framework and Business Code

Generate framework-owned code completely:

- project structure, dependency metadata, YAML sections, names, and references;
- built-in Action wiring and Resource declarations whose arguments the user chose;
- typed custom Action and Tool signatures with the target-version imports;
- runtime Skill directories and valid frontmatter when the user requests them;
- the Flink DataStream/Table entry point and MiniCluster submission path.

Scaffold business-owned code unless the user explicitly requests its implementation
and provides enough behavior to implement it:

- custom Action and Tool bodies;
- service clients, REST paths, queries, authentication, and response semantics;
- domain models and transformations not specified by the user;
- prompt policy, diagnosis or decision procedures, and Tool result interpretation;
- runtime Skill instructions and allowed commands;
- simulated services, fake Tool results, and test doubles.

A function scaffold must import or compile and expose the exact signature referenced
by YAML, but its body should contain a focused TODO and raise
`NotImplementedError` or `UnsupportedOperationException`. A runtime Skill scaffold
contains its confirmed `name` and `description`, followed by a TODO for the user; do
not expand a one-line business goal into a runbook. Do not add tests that pretend
placeholder business behavior is implemented, and do not replace the placeholder
with a mock merely to make a behavior test pass.

When no custom Tool signature was supplied, use the least committal supported shape,
normally one `String`/`str` request and one `String`/`str` result. Name the function
from the stated capability, document that the request/result contracts are TODOs,
and keep the body explicitly failing. This is a scaffold convention, not a claim
that the eventual business API should use strings.

## Minimal Layouts

Python YAML application:

```text
app/
├── .venv/                    # only when selected; generated locally, never committed
├── requirements.txt
├── .gitignore
├── agent.yaml
├── actions.py
├── tools.py                  # when the Agent declares local Tools
├── types.py
├── main.py
├── resources/
│   └── skills/<skill-name>/SKILL.md  # `paths` distribution only
└── tests/
```

Java YAML application:

```text
app/
├── pom.xml
└── src/
    ├── main/java/com/example/agent/{Actions,Types,Main}.java
    ├── main/resources/
    │   ├── yaml/agent.yaml
    │   └── skills/<skill-name>/SKILL.md  # `classpath` only
    └── test/java/com/example/agent/
```

For Python `package` distribution, generate an installable package and place Skills
under that package's configured package-data path instead of the flat `resources/`
directory. For Java `classpath` distribution, keep Skills under
`src/main/resources` so Maven includes them in the application JAR. For `urls`, do
not copy the ZIP into the application artifact. Apply the user's confirmed choice
consistently in YAML, project metadata, packaging, and verification.

Adapt to the target repository rather than forcing these layouts. Keep a streaming
file source pointed at input data only; do not point it at a parent directory that
also contains Skill or YAML assets.

For every new application, generate the actual dependency project and a remote-style
Flink entry point. Follow `local-development.md`; an Agent definition without a
MiniCluster submission path is incomplete. When business functions are still
placeholders, keep deployment-only and behavior verification claims separate.
