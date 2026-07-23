# Gemini CLI Interaction Adapter

Use this adapter only when explicit host context identifies Gemini CLI.

When the `ask_user` communication tool is present in the current tool catalog, call
it with one single-select question for the current gate and wait for the answer.
Provide concise option descriptions, put a recommended option first only when the
gate has one, and keep multi-select disabled. The YAML implementation-language gate
has no recommendation; present Python and Java with parallel descriptions and no
recommendation label.

If the live schema cannot fit all valid choices in one question, preserve every
choice with a meaningful hierarchy or short paged selectors. Use the complete
numbered fallback when splitting would obscure the choices.

Gemini CLI can exclude interactive tools in non-interactive or ACP-style surfaces.
If `ask_user` is absent or errors, read and follow [generic.md](generic.md). Do not
confuse the communication tool with an approval-policy decision of the same name,
change Gemini settings, or silently choose a default. Follow the live tool schema
when its arguments differ from this bundled snapshot.
