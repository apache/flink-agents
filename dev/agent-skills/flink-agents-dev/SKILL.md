---
name: flink-agents-dev
description: Use when building, scaffolding, modifying, debugging, converting, or verifying Apache Flink Agents applications, including Flink Agents YAML, Workflow Agent, ReAct Agent, Actions, Resources, MCP servers, vector stores, runtime skills, Python, or Java.
---

# Developing Flink Agents Applications

## Core Model

Model every application as four connected parts:

1. **Resources**: named models, prompts, tools, skills, vector stores, MCP servers,
   and connections.
2. **Actions**: event handlers that use Resources and emit events.
3. **Orchestration**: trigger conditions and emitted-event graph.
4. **Implementation**: Python/Java functions, types, and runner.

Skills, vector stores, MCP, RAG, and memory are combinations of these parts.

The Agent API language and custom Action/Tool language do not constrain every
Resource implementation. Chat-model connections/setups, embedding-model
connections/setups, and vector stores support Python/Java bridging in both
directions in the bundled snapshot. For those Resource types, offer every
target-version implementation supported natively or through the bridge, regardless
of whether the application uses YAML, direct Python, or direct Java. Treat the
selected implementation's language as part of that Resource choice; do not add a
separate application-wide cross-language confirmation. Do not generalize this rule
to Resource types that lack a verified bridge.

## Source Authority

This skill must work from its installed directory; never assume the user cloned the
Flink Agents repository. Resolve bundled paths relative to this `SKILL.md`.

Inspect the target version and conventions. Use sources in this order:

1. Target application code, dependency metadata, installed package/JAR APIs, and tests.
2. Version-matching Flink Agents schema, docs, examples, or source when available.
3. This skill's references and [bundled YAML schema](assets/yaml-schema.json) as
   the offline baseline.

The bundled schema and patterns describe the Flink Agents revision that published
this skill. If the target dependency differs, prefer its matching contracts and
state any compatibility assumption that could not be verified.

Do not invent APIs, versions, or commands from memory.

Before presenting code as runnable, resolve every nontrivial import, constructor,
method, descriptor argument, and dependency coordinate against those sources. If a
contract cannot be resolved, either use a documented alternative or label the
fragment as pseudocode and state exactly what remains unresolved. Never create an
adapter or wrapper merely to make a speculative API look complete.

## Definition Strategy

| Situation | Recommendation |
|---|---|
| New Workflow Agent or rewired event graph | Offer YAML plus Python/Java implementation files |
| Existing YAML application | Preserve and extend YAML |
| Existing programmatic Agent | Preserve its current API unless conversion is requested |
| `ReActAgent`, unsupported YAML surface, or explicit code-only request | Direct Python or Java API |

For a new application, present YAML, direct Python API, and direct Java API as an
explicit choice after versions are confirmed. A recommendation explains the
tradeoff; it is not permission to select the API for the user.

YAML does not declare an agent `type`. Do not add `type: workflow` or
`type: react`; `type` selects an implementation language where the schema allows it.

YAML does not choose the business implementation language either. For a new YAML
application, ask the user to choose Python or Java before generating files. For an
existing application, detect and preserve the language from build metadata, source
files, function references, and explicit YAML `type` fields. Do not default to
Python merely because omitted YAML `type` fields currently resolve to Python.

Workflow Agents already include built-in chat, tool-call, and context-retrieval
Actions. A model reasoning/tool loop can therefore remain YAML-defined. Choose
`ReActAgent` only when the user explicitly wants that programmatic abstraction, the
existing application already uses it, or a required ReAct-specific surface is not
available through YAML.

## Scaffolding Boundary

Complete framework-owned wiring, but do not invent business behavior. Unless the
user explicitly asks for an implementation and supplies its contract, generate only
an importable or compilable signature skeleton for each custom Action, Tool, domain
client, data transformation, and runtime Skill. Preserve user-provided names,
parameters, types, and descriptions. When those details are absent, derive a stable
name from the stated capability and use a minimal neutral signature such as one
opaque string request and string result; mark unresolved input fields and result
types with focused TODOs and fail explicitly with `NotImplementedError` or
`UnsupportedOperationException`.

Do not author domain rules, REST endpoints, diagnostic procedures, prompts, data
models, Tool results, runtime Skill instructions, or test doubles from a high-level
application idea. Do not propose a standard REST API, MCP server, or mock backend as
the implementation of a custom Tool. Do not ask the user to lock a business input
identity schema, deployment platform, service API, authentication design, or log/
metric backend merely to scaffold the application. Generate the neutral skeleton
and leave those choices as TODOs for the user. Built-in Flink Agents Actions are
framework behavior and need no generated implementation.

## Interaction Discipline

For a new or empty project, use sequential decision gates. Ask only the current
gate, wait for the answer, and then continue. Never send one proposed baseline that
bundles versions, API, implementation language, runtime version, Resource
implementations, business backends, and mock behavior.

Present every closed set of choices through a native structured single-select UI
when one is available. Before the first closed gate, identify the current host from
explicit system and tool context, then read exactly one matching interaction
adapter:

- Codex: [platforms/codex.md](references/platforms/codex.md)
- Claude Code: [platforms/claude-code.md](references/platforms/claude-code.md)
- Gemini CLI: [platforms/gemini-cli.md](references/platforms/gemini-cli.md)
- Qoder: [platforms/qoder.md](references/platforms/qoder.md)
- unknown or unsupported host: [platforms/generic.md](references/platforms/generic.md)

Treat the tools exposed in the current session and mode as authoritative. A platform
adapter may name an optional host tool, but the core workflow must not require that
tool, install host-specific metadata, or add host configuration to the generated
application. Follow any mode prerequisite defined by the selected adapter before
falling back. In particular, the Codex adapter pauses and asks the user to enter
Plan mode before the first gate so Codex can expose its native selector, then pauses
again after the final gate and asks the user to return to Default execution mode
before any implementation work. If the matching native tool remains unavailable
after the adapter's prerequisite or errors, follow the adapter's fallback rule.
Never use an open-ended text question when the valid options are already known. When
a gate has a recommendation, place it first and label it `(Recommended)`, but do not
preselect or continue without the user's answer. The YAML implementation-language
gate intentionally has no recommendation: present Python and Java as equal peer
options with parallel descriptions and no `(Recommended)` label.

Use this order:

1. Ask for the Flink Agents version. Wait. Then offer only compatible Flink versions
   and wait for the Flink choice. Do not mention Python/JDK versions, an Agent API,
   a model provider, or business architecture in these version questions.
2. Ask the user to choose YAML API, direct Python API, or direct Java API. Wait.
3. Only when YAML is selected, ask whether custom Actions, function Tools, and the
   Flink entry point use Python or Java. Resource implementations are selected
   independently at their own gates. Direct Python or Java API already determines
   the application-code choice. Do not recommend, preselect, or imply a preference
   for either YAML implementation language. Only now resolve a compatible Python or
   JDK version.
4. Inventory the Resources required by the user's stated design. Resolve real
   descriptor-backed framework integrations one at a time and wait before moving to
   the next integration. Assign a deterministic internal name, ask for its
   implementation class or documented alias, and stop the interview for that
   Resource. Inspect the target-version constructor/descriptor and generate every
   mandatory configuration key as `TODO_REQUIRED_<FIELD>` for the user to fill.
   For chat-model connections/setups, embedding-model connections/setups, and vector
   stores, do not filter candidates by the Agent API or application-code language.
   Present all verified Python and Java implementations, label each candidate's
   implementation language when needed for disambiguation, and generate the matching
   bridge declaration and runtime dependencies after selection.
   Do not ask for model identifiers, endpoints, credential values/mechanisms,
   provider options, Skill source paths/URLs/packages, or other Resource arguments.
   Custom Actions, function Tools, domain clients, Prompt content, runtime Skill
   instructions, business input schemas, and backend platforms are not integration
   gates: scaffold them without a business interview. Do not ask the user to name a
   single Resource or repeat a generated reference. Ask about naming only when
   multiple Resources need semantic disambiguation, an existing external reference
   constrains the name, or the user requested a naming convention.
5. As soon as the confirmed design first requires a Python runtime, pause before
   environment creation or dependency installation. Unless the existing project
   already declares its environment unambiguously, inspect compatible local Python
   executables/environments and ask whether to reuse one of them or create a
   project-local `.venv`. Wait for the choice and use the selected interpreter for
   every install, import, test, and local run. This conditional gate fires
   immediately after the choice that introduces Python; do not delay it until the
   end of the Resource interview.
6. After every framework decision is confirmed, complete any post-interview handoff
   required by the selected host adapter. Once the host is in an execution-capable
   mode, design the Action graph and generate the project.

For Codex, completing the gates does not itself authorize implementation in Plan
mode. Follow the Codex adapter's execution-mode handoff, stop for the user to switch
back, and begin file edits only after the session continues in Default mode. Other
hosts follow their own adapter and need no Codex-specific mode prompt.

Do not infer OpenAI, Ollama, any model name, an environment-variable credential,
Skill distribution, MCP, a vector store, or a domain-service protocol. After a
framework implementation is selected, do not run a second configuration interview.
Generate a declaration or builder scaffold that names every verified mandatory
argument and leaves its value explicit for the user. For YAML string fields, use a
clear placeholder such as `TODO_REQUIRED_API_KEY`; for direct APIs, generate a
compilable factory skeleton that lists the required arguments and fails explicitly
until they are filled. If the user already supplied a value or explicitly requested
plaintext in local YAML, use that instruction; keep supplied secrets out of tracked
files and output. Never claim `${ENV_VAR}` is interpolated unless the target loader
or provider actually implements it.

The conditional Python-environment gate is dependency management, not Resource
configuration. It still runs when a selected Resource introduces Python, but it asks
only which concrete compatible Python environment to use, never provider values.

## Build Workflow

1. Inspect the project, build metadata, existing definitions, and confirmed user
   requirements. Preserve explicit existing decisions. Read
   [application patterns](references/application-patterns.md).
2. For a new project, complete the version gate in
   [local development](references/local-development.md): Flink Agents first, then a
   compatible Flink version. Do not generate files or combine later decisions into
   this question.
3. Complete the API gate: YAML, direct Python, or direct Java. For YAML, read
   [YAML patterns](references/yaml-patterns.md) and use the
   [bundled YAML schema](assets/yaml-schema.json) unless a target-version schema is
   available.
4. If YAML was selected, complete the implementation-language gate. Then read
   [Python patterns](references/python-patterns.md) or
   [Java patterns](references/java-patterns.md) for the application code. When a
   bridge-supported Resource uses the other language, also read that language's
   cross-language Resource section; selecting that Resource is already explicit
   confirmation.
5. Inventory each Resource and assign stable names automatically. Ask only which
   documented implementation alias/class to use for actual framework integrations.
   For bridge-supported types, build this choice from both Python and Java
   implementations instead of the application-code language alone. Inspect the
   selected implementation, add its integration and bridge dependencies, and
   scaffold all mandatory configuration keys without asking for their values.
   Generate custom
   Tool/Action/Prompt/runtime-Skill business scaffolds without asking the user to
   choose a domain platform or complete an input contract; generate and record all
   cross-references without asking the user to repeat internal identifiers. Never
   select a provider, model, endpoint, credential source, business backend, or
   integration from the application domain alone.
6. When runtime Skills are requested, preserve an explicit source in an existing
   application. For a new application, generate a minimal runtime `SKILL.md` business
   scaffold plus a source-configuration TODO that lists the valid `paths`, `urls`,
   Python `package`, and Java `classpath` forms. Do not ask for loading paths or
   distribution, and never inspect, copy, or offer to reuse Skills installed in the
   coding-agent host, such as a local `flink-diag`; coding-agent Skills and Flink
   runtime Skills are different artifacts. Read
   [YAML patterns](references/yaml-patterns.md#runtime-skills).
7. Draw the framework Action graph from input through Resource interactions to
   output and errors. Use TODO-marked opaque payloads where business event fields or
   branching semantics are unspecified; do not turn those TODOs into more gates.
8. Use only documented built-in Actions. Generate a resolvable, correctly typed
   signature skeleton for every other Action and reference it as
   `<module-or-class>:<qualname>`; leave its business body for the user unless they
   explicitly requested implementation.
9. Resolve the concrete API calls and dependency coordinates before generating the
   complete executable project. For Java, generate a Maven project with
   `flink-agents-api`, `flink-agents-plan`, `flink-agents-runtime`, and only the
   integrations actually used; declare all Flink Agents and Flink dependencies as
   `provided`. Whenever the design requires Python, generate source and dependency
   files, resolve the Python environment choice when not already declared, and
   install the resolved Flink Agents, PyFlink, and integration dependencies into the
   selected existing environment or project-local `.venv`. Read
   [local development](references/local-development.md). Preserve versions already
   selected by the target project; never guess one.
10. Connect the Agent to a Flink DataStream or Table through the public factory backed
   by `RemoteExecutionEnvironment`. Local validation submits that same remote-style
   job to a MiniCluster. Never use a local Agents environment, a no-argument factory,
   `from_list`/`to_list`, or their Java equivalents.
11. Run the checks in [verification](references/verification.md) before claiming
   the application is valid or runnable.

## Required Output

- Complete framework files or edits, including every custom function signature and
  a remote-style Flink job entry point.
- A runnable Maven project for Java and, whenever Python is required, pinned Python
  dependency input plus the user-selected existing environment or populated
  project-local `.venv`.
- User-confirmed Flink Agents and Flink versions. Never silently choose the bundled
  recommended versions for a new project.
- A user-confirmed YAML, direct Python, or direct Java API choice for every new
  application.
- A user-confirmed Python or Java application-code language for every new YAML
  application, reflected consistently in project layout, custom Action/Tool function
  references, and the Flink entry point. Do not use it to filter bridge-supported
  Resource implementations.
- For applications using runtime Skills, a user-fillable Skill business scaffold
  and source-configuration TODO. Preserve existing source configuration, but do not
  ask a new-project user to choose distribution or reuse coding-agent host Skills.
- Resource declarations built from user-selected implementation aliases/classes,
  including independent Resource implementation language and bridge wiring where
  supported, with every verified mandatory provider key present and marked for user
  input.
  Keep model, endpoint, credential, and optional provider values unresolved instead
  of interviewing for them or choosing defaults.
- Deterministic Resource names and references generated by the coding agent. Do not
  require the user to name ordinary Connection, Setup, Prompt, Skill container,
  VectorStore, or MCP Resource identifiers when there is no ambiguity.
- When the user chooses plaintext credentials for local testing, a loader-compatible
  local YAML that is excluded from version control and actually used by the local
  run command. Do not replace this explicit choice with programmatic registration.
- Explicit user-fillable business skeletons for custom Actions, Tools, prompts,
  runtime Skills, domain clients, secrets, endpoints, and external data. Keep
  framework wiring and signatures concrete instead of replacing them with a
  checklist or fabricated business implementation.
- A final consolidated `User must provide` list for unresolved business input
  fields, platform clients, authentication, queries, response mapping, and domain
  behavior. These items must not block project scaffolding or become Agent workflow
  decisions unless the user explicitly asks for their implementation.
- Exact commands that match the target repository's build tooling; no guessed
  package, Flink, provider, model, or plugin versions.
- Framework snippets whose imports and API calls resolve in the target version.
  Business skeletons must import or compile, fail explicitly when invoked, and be
  labeled user-fillable rather than runnable behavior.
- Evidence separated into schema, load/compile, tests, and runtime.
- A clear statement for every check that was not run or requires an external service.

## Quick Reference

| Task | Read |
|---|---|
| Present closed choices in the current coding agent | One matching file under [platforms/](references/platforms/generic.md) |
| Select Agent/API shape; design Resources and event graph | [application-patterns.md](references/application-patterns.md) |
| Author or review YAML; resolve names and functions | [yaml-patterns.md](references/yaml-patterns.md) |
| Scaffold runtime Skill business and source TODOs | [runtime Skills](references/application-patterns.md#scaffold-runtime-skills) |
| Scaffold Python Actions, Tools, types, or runner | [python-patterns.md](references/python-patterns.md) |
| Scaffold Java Actions, Tools, resources, or runner | [java-patterns.md](references/java-patterns.md) |
| Select versions, generate dependencies, and submit to MiniCluster | [local-development.md](references/local-development.md) |
| Validate schema, references, imports, compilation, and execution claims | [verification.md](references/verification.md) |
| Validate YAML without a source checkout | [bundled YAML schema](assets/yaml-schema.json) |
