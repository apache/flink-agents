# Qoder Interaction Adapter

Use this adapter only when explicit host context identifies Qoder.

The bundled publication snapshot does not assume a stable Qoder structured-question
tool name or argument contract. Inspect the current session's tool catalog. If it
explicitly exposes a structured single-select question tool, call it according to
the live schema for one gate at a time and wait for the answer.

For the YAML implementation-language gate, present Python and Java as equal peer
options with parallel descriptions. Do not mark either language as recommended or
preselect one.

Otherwise read and follow [generic.md](generic.md). Do not guess that a tool from
Codex, Claude Code, or Gemini CLI exists in Qoder, and do not add Qoder-specific
metadata to the generated Flink Agents project.
