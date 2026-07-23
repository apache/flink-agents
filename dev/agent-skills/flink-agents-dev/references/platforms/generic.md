# Generic Interaction Adapter

Use this adapter when the host is unknown, no dedicated adapter exists, or a
dedicated adapter's native question tool is unavailable.

## Closed Choices

When this adapter was selected directly, use a structured single-select question
tool only if the current tool catalog explicitly exposes it and its argument
contract is available. Do not guess a tool name or argument shape. When another
adapter reached this file because its native tool failed, skip native discovery and
go directly to the numbered list; do not retry the failed tool.

Otherwise render all valid choices as a numbered list:

```text
Select the API:
1. YAML API (Recommended)
2. Direct Python API
3. Direct Java API

Reply with 1, 2, or 3.
```

Put one option on each line. When a gate has a recommended option, keep it first and
label it, but do not preselect it. The YAML implementation-language gate has no
recommendation: list Python and Java with parallel descriptions and no
`(Recommended)` label. Stop after the current question and wait for an explicit
answer. Do not combine gates, continue on silence, or replace a known enumeration
with an open-ended question.

For a non-interactive or headless run, emit the same numbered question and stop.
The caller must resume or rerun with the selected value; never choose a default to
keep automation moving.
