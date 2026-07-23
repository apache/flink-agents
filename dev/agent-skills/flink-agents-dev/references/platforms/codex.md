# Codex Interaction Adapter

Use this adapter only when explicit host context identifies Codex.

## Capability Check

The current session and mode's tool contract is authoritative. Codex exposes its
native closed-choice UI through `request_user_input` in Plan mode. Before the first
decision gate, check the explicit collaboration mode and the available tool
contract.

When the session is not in Plan mode, respond with one short message in the user's
language that conveys:

> Flink Agents setup has several required choices. Switch Codex to Plan mode with
> `/plan` or `Shift+Tab`, then continue this request so I can present one native
> selector at a time.

Stop after that message. Do not present a numbered choice list, begin project
generation, or silently choose defaults. When the user continues in Plan mode,
resume at the first unresolved gate without repeating already supplied facts.

When Plan mode is active and `request_user_input` is exposed and callable, use it
for every closed decision gate. Follow [generic.md](generic.md) only when the user
explicitly declines or cannot enter Plan mode, or when Plan mode is active but the
tool is still absent or returns an availability error. Do not retry an unavailable
tool or invent an alias.

## Return to Execution Mode

After the last required decision gate is answered, do not start generating files,
installing dependencies, or running verification while Codex remains in Plan mode.
Respond with one short message in the user's language that conveys:

> All required Flink Agents choices are confirmed. Switch Codex back to Default
> (execution) mode with the mode selector, or press `Shift+Tab` until Default mode
> is selected. Then continue so I can generate the project and run verification.

Stop after that message. When the user continues in Default mode, resume directly
at the build workflow with the confirmed decisions. Do not ask them to enter Plan
mode again, repeat completed selectors, or require a summary of their previous
answers. Return to Plan mode only if a genuinely new unresolved closed decision is
discovered before implementation can safely continue.

## Native Single Select

Call `request_user_input` with exactly one question for the current gate and wait
for its result before doing more work. Omit `autoResolutionMs` because every gate
requires an explicit answer. Use stable `snake_case` IDs, a short header, concise
labels, and one-sentence descriptions. When a gate has a recommendation, put it
first and suffix its label with `(Recommended)`. The YAML implementation-language
gate has no recommendation: give Python and Java parallel descriptions and suffix
neither label.

For example, the API gate maps to:

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
would alter or obscure the choices, follow the complete numbered list in
[generic.md](generic.md).
