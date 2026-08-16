# Claude Code Interaction Adapter

Use this adapter only when explicit host context identifies Claude Code.

Capability detection and fallback are owned by
[Interaction Discipline](../../SKILL.md#interaction-discipline). Read this adapter
only after it selects an exposed `AskUserQuestion` contract. Ask exactly one
question for the current gate, use its option list, set `multiSelect` to `false`, and
wait for the answer before continuing. When a gate has a recommendation, put it
first and label it `(Recommended)`. The YAML
implementation-language gate has no recommendation; present Python and Java with
parallel descriptions and no recommendation label.

If the live schema cannot fit all valid choices in one question, preserve every
choice with a meaningful hierarchy or short paged selectors. Return control to
[Interaction Discipline](../../SKILL.md#interaction-discipline) when splitting
would obscure the choices.

If `AskUserQuestion` reports an availability error, return control to
[Interaction Discipline](../../SKILL.md#interaction-discipline). Do not add it to
allowed tools or modify Claude Code settings. The generated Flink Agents project
must not contain Claude Code configuration.

Follow the argument schema exposed by the running Claude Code version. Do not copy
a stale parameter shape from this reference when the live tool contract differs.
