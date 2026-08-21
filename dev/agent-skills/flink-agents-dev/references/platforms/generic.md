# Generic Interaction Adapter

Use this adapter only when selected by the authoritative
[Interaction Discipline](../../SKILL.md#interaction-discipline). That policy
supplies either an exposed generic structured-question contract or the decision to
render the numbered fallback; do not redetect capabilities or change that selection
here.

## Closed Choices

When [Interaction Discipline](../../SKILL.md#interaction-discipline) supplied a
generic structured-question contract, use its live argument schema for one
single-select gate and wait. When it selected the numbered fallback, render all
valid choices as a numbered list without trying a tool first. For example, when the
selected Flink Agents version supports all three API surfaces:

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
