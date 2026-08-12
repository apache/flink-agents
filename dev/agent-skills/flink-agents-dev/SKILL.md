---
name: flink-agents-dev
description: Use when building, scaffolding, modifying, debugging, converting, or verifying Apache Flink Agents applications, including Flink Agents YAML, Workflow Agent, ReAct Agent, Actions, Resources, MCP servers, vector stores, runtime skills, Python, or Java. Do not use for ordinary Flink jobs that do not use Flink Agents.
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
3. This skill's [YAML contract manifest](assets/yaml-contracts.yaml), matching
   bundled schema, and references as the offline baseline.

Select contracts by the target Flink Agents version, not by the version that
published this skill. The manifest maps released versions to complete schemas and
marks versions without YAML support. The unversioned
[main schema](assets/yaml-schema.json) describes only the repository revision that
published this skill. Never validate a released application against that schema
unless the manifest maps the selected version to it.

Do not invent APIs, versions, or commands from memory.

Before presenting code as runnable, resolve every nontrivial import, constructor,
method, descriptor argument, and dependency coordinate against those sources. If a
contract cannot be resolved, either use a documented alternative or label the
fragment as pseudocode and state exactly what remains unresolved. Apply
[Provider Contract Triage](#provider-contract-triage) below to external
descriptor-backed Providers before loading bundled references. This blocked
Provider path is different from an unresolved business contract, which still
receives a user-fillable skeleton.

## Preflight

Before loading a platform adapter, asking a decision gate, or generating files:

1. Confirm that the target is a Flink Agents application or is being converted into
   one. Leave ordinary Flink jobs outside this workflow.
2. Inspect the complete user request, existing project, dependency metadata, source,
   configuration, and tests. Resolve every framework decision already answered by
   that evidence. The latest explicit user choice takes precedence; treat a conflict
   with existing project metadata as an intentional requested migration and report
   it rather than silently restoring the old value.
3. Record each required framework decision as `confirmed` or `unresolved`: Flink
   Agents version, Flink version, API, YAML application language when applicable,
   each descriptor-backed Resource implementation, and the Python environment when
   Python is required.
4. Run Provider Contract Triage for every concrete external descriptor-backed
   Provider already named by the request or project. When the implementation is an
   unresolved gate, run the triage immediately after the user selects it.
5. Build the ordered gate list from only the `unresolved` decisions. If the list is
   empty, skip host detection and interaction adapters and proceed directly to
   contract resolution and implementation.
6. For YAML, select the exact target-version schema through
   [yaml-contracts.yaml](assets/yaml-contracts.yaml). Filter the API choices when the
   manifest says that the selected version has no YAML API. If a requested YAML
   version has no matching schema, stop YAML generation and request the exact
   target-version schema or source; never substitute the main schema.

Keep this decision record current after every answer. It prevents repeated
questions and ensures that later references operate only on confirmed versions and
capabilities.

### Provider Contract Triage

This is an early-exit pass, not a later Build Workflow step. Run it as soon as a
concrete external descriptor-backed Provider class or alias is confirmed and before
reading any file under `references/`.

Inspect only evidence that can establish that Provider's contract:

1. Explicit user-supplied JARs, packages, source, tests, or documentation.
2. The target project's dependency metadata and the exact installed artifact named
   by that metadata.
3. Version-matching authoritative Provider source or documentation already available
   at a known location.

Scope every inspection command to the target application's dependency files,
configuration, source roots, and explicitly named artifacts. Exclude the installed
Skill directory containing this `SKILL.md`, including project-local `.agents/`, and
exclude host metadata such as `.codex/`, `.claude/`, tool caches, and unrelated home
or package caches. Do not run an unscoped recursive search over `.` when it would
traverse those paths.

Do not load general application, YAML, language, local-development, or verification
references to compensate for a missing Provider artifact. A general YAML schema can
validate descriptor shape but cannot establish a Provider's dependency coordinate,
constructor, or forwarded mandatory arguments. When the user explicitly states that
no Provider artifact or contract source is available, treat that as evidence; check
the target project metadata for an existing dependency, but do not search unrelated
caches or examples.

The triage must establish the implementation class or alias, dependency coordinate
or package, constructor/factory contract, and every mandatory descriptor argument.
If any element remains unverifiable:

- preserve all working dependency, source, and configuration files;
- stop the dependent integration immediately, before the normal Build Workflow;
- do not generate a descriptor, adapter, wrapper, placeholder dependency, or tests;
- report the exact sources checked, each missing contract element, and the minimum
  JAR, package, source, test, or documentation needed to continue.

List a source under `checked` only when its content was actually inspected during
the triage. Do not claim that a bundled schema, reference, cache, or documentation
was checked merely because `SKILL.md` names it.

Return that blocked report for a Provider-only request. Continue other work only
when the user explicitly requested an independent change that does not depend on the
blocked Provider. If the complete Provider contract is verified, proceed with the
normal workflow using that evidence.

## Definition Strategy

| Situation | Recommendation |
|---|---|
| New Workflow Agent or rewired event graph | Offer YAML plus Python/Java implementation files |
| Existing YAML application | Preserve and extend YAML |
| Existing programmatic Agent | Preserve its current API unless conversion is requested |
| `ReActAgent`, unsupported YAML surface, or explicit code-only request | Direct Python or Java API |

For a new application, derive the available API choices from the confirmed target
version. Present YAML, direct Python API, and direct Java API when all three are
supported; otherwise omit unsupported surfaces. A recommendation explains the
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

Complete framework-owned wiring, but do not invent business behavior. Before
implementing each custom Action, Tool, domain client, data transformation, Prompt,
or runtime Skill, classify these contract elements from only explicit user
requirements, existing code, and existing tests:

| Contract element | `supplied` means |
|---|---|
| Input | Required fields and their meaning are defined |
| Output | Result fields or emitted Events are defined |
| Transformation | Input-to-output or message construction is defined |
| Side effects | External calls, state changes, and authentication boundary are defined |
| Errors | Required failure and fallback behavior is defined |

Anything not established by those sources is `unresolved`; a high-level capability
name is not a supplied contract. When behavior depends on an unresolved element,
generate only an importable or compilable signature skeleton with focused TODOs and
an explicit `NotImplementedError` or `UnsupportedOperationException`. Preserve
user-provided names, parameters, types, and descriptions. When those are absent,
derive a stable capability name and use the narrowest framework-compatible
signature; for a function Tool or domain client with no typed contract, use one
opaque string request and string result.

An unresolved skeleton must not emit business Events, transform payloads, compose
Prompt or chat messages, call a backend, return business data, or provide fallback
behavior. Tests may check import/compilation, signature and YAML reference
resolution, and explicit failure, but must not assert business behavior that was
not supplied.

Do not author domain rules, REST endpoints, diagnostic procedures, prompts, data
models, Tool results, runtime Skill instructions, or test doubles from a high-level
application idea. Do not propose a standard REST API, MCP server, or mock backend as
the implementation of a custom Tool. Do not ask the user to lock a business input
identity schema, deployment platform, service API, authentication design, or log/
metric backend merely to scaffold the application. Generate the neutral skeleton
and leave those choices as TODOs for the user. Built-in Flink Agents Actions are
framework behavior and need no generated implementation.

## Interaction Discipline

This section is the sole authority for interaction capability detection, fallback
selection, retry behavior, and host-mode handling. Platform adapters only describe
how to encode a question for a tool that this section selected; other references
must point here instead of restating this policy.

Process the unresolved decisions from Preflight as sequential gates. Ask only the
current gate, wait for the answer, update the decision record, and continue. Never
send one proposed baseline that bundles versions, API, implementation language,
runtime version, Resource implementations, business backends, and mock behavior.

Only when at least one unresolved closed gate remains, identify the current host
from explicit system and tool context. Use this table to select the sole candidate
tool and adapter for the gate:

| Host | Candidate structured tool | Adapter |
|---|---|---|
| Codex | `request_user_input` | [codex.md](references/platforms/codex.md) |
| Claude Code | `AskUserQuestion` | [claude-code.md](references/platforms/claude-code.md) |
| Gemini CLI | `ask_user` communication tool | [gemini-cli.md](references/platforms/gemini-cli.md) |
| Qoder | Explicitly exposed structured single-select tool, if any | [qoder.md](references/platforms/qoder.md) |
| Unknown or unsupported | Explicitly exposed structured single-select tool, if any | [generic.md](references/platforms/generic.md) |

Treat the current tool contract as the only capability signal. When the table's
candidate is exposed and callable, read its adapter to encode one question. An
adapter must not perform capability discovery or select a fallback. If the candidate
is absent, returns an availability error, or cannot represent the complete valid
option set without altering or obscuring it, immediately read
[generic.md](references/platforms/generic.md) and use its numbered fallback. Do not
retry the tool, ask the user to change modes, require a slash command or keyboard
shortcut, perform a post-interview handoff, add host metadata, or install host
configuration in the generated application.

Never use an open-ended text question when the valid options are already known.
When a gate has a recommendation, place it first and label it `(Recommended)`, but
do not preselect or continue without the user's answer. The YAML
implementation-language gate intentionally has no recommendation: present Python
and Java as equal peer options with parallel descriptions and no `(Recommended)`
label.

Use this order:

1. If unresolved, ask for the Flink Agents version and wait. Then offer only
   compatible Flink versions and wait for the Flink choice. Do not mention
   Python/JDK versions, an Agent API, a model provider, or business architecture in
   these version questions.
2. If unresolved, ask the user to choose among the APIs supported by the confirmed
   Flink Agents version. Offer YAML only when its exact contract is available. Wait.
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
   Resource. Immediately run Provider Contract Triage after the implementation
   choice; it verifies the target-version constructor and descriptor. If it passes,
   generate every mandatory configuration key as `TODO_REQUIRED_<FIELD>` for the
   user to fill. Do not ask about another Resource or load general references before
   the triage result.
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
6. After every framework decision is confirmed, design the Action graph and generate
   the project. Do not add a host-specific handoff.

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

1. Run Preflight, including Provider Contract Triage for every concrete external
   Provider already named by the request or project. If a Provider is blocked, issue
   its report and stop before reading any bundled reference.
2. Read [application patterns](references/application-patterns.md), then ask only the
   ordered unresolved gates. Do not repeat decisions already confirmed by the user
   or project.
3. When versions are unresolved, complete the version gates in
   [local development](references/local-development.md): Flink Agents first, then a
   compatible Flink version. Do not generate files or combine later decisions into
   these questions.
4. Complete the API gate with only APIs supported by the confirmed target version.
   For YAML, read [YAML patterns](references/yaml-patterns.md), select the exact
   schema through [yaml-contracts.yaml](assets/yaml-contracts.yaml), and reject
   generation when no exact contract is available.
5. If YAML was selected, complete the implementation-language gate. Then read
   [Python patterns](references/python-patterns.md) or
   [Java patterns](references/java-patterns.md) for the application code. When a
   bridge-supported Resource uses the other language, also read that language's
   cross-language Resource section; selecting that Resource is already explicit
   confirmation.
6. Inventory each Resource and assign stable names automatically. Ask only which
   documented implementation alias/class to use for actual framework integrations.
   For bridge-supported types, build this choice from both Python and Java
   implementations instead of the application-code language alone. Inspect the
   selected implementation through Provider Contract Triage before loading more
   references or moving to another integration. When it passes, add its integration
   and bridge dependencies and scaffold all mandatory configuration keys without
   asking for their values.
   Classify every custom Tool/Action/Prompt/runtime-Skill business contract before
   generating its skeleton; do not ask the user to choose a domain platform or
   complete an unresolved input contract. Generate and record all cross-references
   without asking the user to repeat internal identifiers. Never select a provider,
   model, endpoint, credential source, business backend, or integration from the
   application domain alone.
7. When runtime Skills are requested, preserve an explicit source in an existing
   application. For a new application, generate a minimal runtime `SKILL.md` business
   scaffold plus a source-configuration TODO that lists the valid `paths`, `urls`,
   Python `package`, and Java `classpath` forms. Do not ask for loading paths or
   distribution, and never inspect, copy, or offer to reuse Skills installed in the
   coding-agent host, such as a local `flink-diag`; coding-agent Skills and Flink
   runtime Skills are different artifacts. Read
   [YAML patterns](references/yaml-patterns.md#runtime-skills).
8. Draw only framework Action graph edges established by built-in contracts or
   supplied business contracts. Where an emitted Event, payload transformation,
   branch, output, or error contract is unresolved, generate the Action signature
   and explicit failure but do not claim or implement that edge. Do not turn those
   TODOs into more gates.
9. Use only documented built-in Actions. Generate a resolvable, correctly typed
   signature skeleton for every other Action and reference it as
   `<module-or-class>:<qualname>`; leave its business body for the user unless they
   explicitly requested implementation.
10. Resolve concrete API calls and dependency coordinates from the target-version
   artifacts before generating the complete executable project. For Java, generate
   a Maven project with
   `flink-agents-api`, `flink-agents-plan`, `flink-agents-runtime`, and only the
   integrations actually used; declare all Flink Agents and Flink dependencies as
   `provided`. Whenever the design requires Python, generate source and dependency
   files, resolve the Python environment choice when not already declared, and
   install the resolved Flink Agents, PyFlink, and integration dependencies into the
   selected existing environment or project-local `.venv`. Read
   [local development](references/local-development.md). Preserve versions already
   selected by the target project; never guess one.
11. Connect the Agent to a Flink DataStream or Table through the public factory backed
   by `RemoteExecutionEnvironment`. Local validation submits that same remote-style
   job to a MiniCluster. Never use a local Agents environment, a no-argument factory,
   `from_list`/`to_list`, or their Java equivalents.
12. Run the checks in [verification](references/verification.md) before claiming
   the application is valid or runnable.

## Required Output

- Complete framework files or edits, including every custom function signature and
  a remote-style Flink job entry point.
- A runnable Maven project for Java and, whenever Python is required, pinned Python
  dependency input plus the user-selected existing environment or populated
  project-local `.venv`.
- User-confirmed Flink Agents and Flink versions. Never silently choose the bundled
  recommended versions for a new project.
- A user-confirmed API choice from the surfaces supported by the selected Flink
  Agents version.
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
  runtime Skills, domain clients, secrets, endpoints, and external data, backed by
  the supplied/unresolved contract classification. Keep framework wiring and
  signatures concrete instead of replacing them with fabricated business
  implementation.
- A final consolidated `User must provide` list for unresolved business input
  fields, platform clients, authentication, queries, response mapping, and domain
  behavior. These items must not block project scaffolding or become Agent workflow
  decisions unless the user explicitly asks for their implementation.
- Exact commands that match the target repository's build tooling; no guessed
  package, Flink, provider, model, or plugin versions.
- Framework snippets whose imports and API calls resolve in the target version.
  Business skeletons must import or compile, fail explicitly when invoked, and be
  labeled user-fillable rather than runnable behavior.
- For YAML, the selected contract key and exact schema path used for validation.
- Evidence separated into schema, load/compile, tests, and runtime.
- A clear statement for every check that was not run or requires an external service.

## Quick Reference

| Task | Read |
|---|---|
| Present closed choices in the current coding agent | [Interaction Discipline](#interaction-discipline), then its selected platform adapter |
| Select Agent/API shape; design Resources and event graph | [application-patterns.md](references/application-patterns.md) |
| Author or review YAML; resolve names and functions | [yaml-patterns.md](references/yaml-patterns.md) |
| Scaffold runtime Skill business and source TODOs | [runtime Skills](references/application-patterns.md#scaffold-runtime-skills) |
| Scaffold Python Actions, Tools, types, or runner | [python-patterns.md](references/python-patterns.md) |
| Scaffold Java Actions, Tools, resources, or runner | [java-patterns.md](references/java-patterns.md) |
| Select versions, generate dependencies, and submit to MiniCluster | [local-development.md](references/local-development.md) |
| Validate schema, references, imports, compilation, and execution claims | [verification.md](references/verification.md) |
| Select and validate YAML without a source checkout | [YAML contract manifest](assets/yaml-contracts.yaml) |
