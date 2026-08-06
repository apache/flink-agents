# Codex Interaction Adapter

Use this adapter only when explicit host context identifies Codex.

## Scope

Capability detection, fallback, retry, and mode behavior are owned exclusively by
[Interaction Discipline](../../SKILL.md#interaction-discipline). Read this adapter
only after that policy has selected Codex and an exposed `request_user_input`
contract. If the call reports an availability error, return control to that policy;
do not select a fallback in this file.

## Native Single Select

Call `request_user_input` with exactly one question for the current gate and wait
for its result before doing more work. Omit `autoResolutionMs` because every gate
requires an explicit answer. Use stable `snake_case` IDs, a short header, concise
labels, and one-sentence descriptions. When a gate has a recommendation, put it
first and suffix its label with `(Recommended)`. The YAML implementation-language
gate has no recommendation: give Python and Java parallel descriptions and suffix
neither label.

For example, when the selected Flink Agents version supports all three surfaces,
the API gate maps to:

```text
request_user_input(
  questions=[
    {
      "header": "Agent API",
      "id": "agent_api",
      "question": "Select the API for this Flink Agents application.",
      "options": [
        {
          "label": "YAML API (Recommended)",
          "description": "Use schema-validated declarative workflow wiring."
        },
        {
          "label": "Direct Python API",
          "description": "Build the application programmatically in Python."
        },
        {
          "label": "Direct Java API",
          "description": "Build the application programmatically in Java."
        }
      ]
    }
  ]
)
```

Follow the argument schema exposed by the running Codex version if it differs from
this snapshot.

## Option Limits

The bundled Codex contract accepts two or three options per question. Preserve all
choices with a short hierarchy rather than dropping options or immediately using a
text list.

The default new-project Flink Agents menu fits in one native selector:

1. `0.3.0 (Recommended)`
2. `0.2.1`
3. `0.1.1`

Do not offer older patches from those minor lines unless an existing project pins
one or the user explicitly requests it. For the four bundled Flink versions, offer
`2.2.1 (Recommended)`, `2.1.3`, and `Older Flink`, then resolve the last choice to
`2.0.2` or `1.20.5`. Apply compatibility filtering before building the hierarchy.

If compatibility filtering leaves one valid value but the live tool requires at
least two options, offer `Use <value> (Recommended)` and `Change previous choice`.
The second option returns to the preceding gate; it is not another version.

For another option set, use meaningful release/provider families when they are
unambiguous. Otherwise use short paged native selectors. If hierarchy or paging
would alter or obscure the choices, return control to
[Interaction Discipline](../../SKILL.md#interaction-discipline).
