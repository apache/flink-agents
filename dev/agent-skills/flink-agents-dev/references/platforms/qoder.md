# Qoder Interaction Adapter

Use this adapter only when explicit host context identifies Qoder.

The bundled publication snapshot does not assume a stable Qoder structured-question
tool name or argument contract. Capability detection and fallback are owned by
[Interaction Discipline](../../SKILL.md#interaction-discipline). Read this adapter
only after it selects an explicitly exposed structured single-select tool. Call that
tool according to the live schema for one gate at a time and wait for the answer.

For the YAML implementation-language gate, present Python and Java as equal peer
options with parallel descriptions. Do not mark either language as recommended or
preselect one.

If the selected tool reports an availability error, return control to
[Interaction Discipline](../../SKILL.md#interaction-discipline). Do not guess that
a tool from Codex, Claude Code, or Gemini CLI exists in Qoder, and do not add
Qoder-specific metadata to the generated Flink Agents project.
