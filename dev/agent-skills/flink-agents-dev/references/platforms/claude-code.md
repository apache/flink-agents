# Claude Code Interaction Adapter

Use this adapter only when explicit host context identifies Claude Code.

When `AskUserQuestion` is present in the current tool catalog, call it for closed
decision gates. Ask exactly one question for the current gate, use its option list,
set `multiSelect` to `false`, and wait for the answer before continuing. When a gate
has a recommendation, put it first and label it `(Recommended)`. The YAML
implementation-language gate has no recommendation; present Python and Java with
parallel descriptions and no recommendation label.

If the live schema cannot fit all valid choices in one question, preserve every
choice with a meaningful hierarchy or short paged selectors. Use the complete
numbered fallback when splitting would obscure the choices.

Tool availability depends on the active Claude Code surface and configuration. If
`AskUserQuestion` is absent or errors, read and follow [generic.md](generic.md).
Do not add it to allowed tools, modify Claude Code settings, retry it repeatedly,
or silently select a default. The generated Flink Agents project must not contain
Claude Code configuration.

Follow the argument schema exposed by the running Claude Code version. Do not copy
a stale parameter shape from this reference when the live tool contract differs.
