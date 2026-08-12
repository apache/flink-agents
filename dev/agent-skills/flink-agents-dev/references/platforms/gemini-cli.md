# Gemini CLI Interaction Adapter

Use this adapter only when explicit host context identifies Gemini CLI.

Capability detection and fallback are owned by
[Interaction Discipline](../../SKILL.md#interaction-discipline). Read this adapter
only after it selects an exposed `ask_user` communication-tool contract. Call it
with one single-select question for the current gate and wait for the answer.
Provide concise option descriptions, put a recommended option first only when the
gate has one, and keep multi-select disabled. The YAML implementation-language gate
has no recommendation; present Python and Java with parallel descriptions and no
recommendation label.

If the live schema cannot fit all valid choices in one question, preserve every
choice with a meaningful hierarchy or short paged selectors. Return control to
[Interaction Discipline](../../SKILL.md#interaction-discipline) when splitting
would obscure the choices.

If `ask_user` reports an availability error, return control to
[Interaction Discipline](../../SKILL.md#interaction-discipline). Do not confuse the
communication tool with an approval-policy decision of the same name or change
Gemini settings. Follow the live tool schema when its arguments differ from this
bundled snapshot.
