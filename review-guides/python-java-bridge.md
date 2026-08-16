# Review Guide: Python-Java Bridge

Load this guide when a PR changes code that crosses the Python-Java boundary:
Pemja entry points, resource or tool wrappers, event and agent-plan
serialization, or type conversion in either direction. It narrows the full
passes in `code_review.md` to the ones that matter most for this area; the
general passes still apply.

## Focused checklist

- When a method lands on a type that exists in both languages, check every
  wrapper carrying it across, not only the two implementations. A wrapper
  inheriting the other side's implementation can fail the call instead of
  crossing it, and one left on the legacy path degrades silently.
- Settle what an explicitly null declarative argument means. A Java descriptor
  lookup cannot distinguish an absent argument from one declared null, while
  Python can, so the same YAML can reach a different conclusion on each side.
- Keep constants shared by both languages in sync: event type strings, resource
  types, YAML aliases, flattened-map keys. A new event type or attribute also
  needs the cross-language snapshots regenerated and committed on both sides.
- Confirm both legs of a conversion carry the same fields. An argument present
  on one leg and missing on the other drops data with no error, and the legs
  usually live in different files.
- Treat bridge entry-point names as a contract. Python function names called
  from Java and Java fully-qualified names resolved from Python are string
  literals, so renaming or moving either breaks only at runtime.
- Keep values that cross the boundary flattened to primitives, strings, lists,
  and maps. Returning an arbitrary object in either direction to a call that
  originated on a non-main interpreter thread can crash the JVM, which is why
  the existing conversions return flat maps.

## Validation

Run both language lanes. A bridge change verified on one side only is untested.

- Java: `mvn --batch-mode test -pl runtime -am`. The `-am` matters here because
  the Java halves of the cross-language snapshot tests live in `api` and
  `plan`, upstream of the module that owns the bridge implementations.
- Python: from `python/`, run `uv sync --extra test`, then `uv pip install
  "apache-flink==$(mvn -q -N --batch-mode -f ../pom.xml help:evaluate
  -Dexpression=flink.version -DforceStdout)"`, then `uv run --no-sync pytest
  flink_agents/runtime flink_agents/api flink_agents/plan`. PyFlink is not a
  declared test dependency and the event types import it, so collection fails
  without it.

Together these run the committed cross-language snapshot tests from both sides.
Dispatch through a real interpreter is only covered by the cross-language
end-to-end modules.

## Examples from past reviews

| Case | Pass it exercises | Review |
|---|---|---|
| A usage-tracking method reached both setup types but not the wrappers, so each inherited an implementation its connection cannot serve: the Python-backed setup never initializes the Java connection, the Java-backed one holds only a resource name. Calls failed instead of crossing, and the connection wrappers fell back to the legacy call, returning no usage. | Whether every wrapper carrying a call across the boundary was updated, including result conversion and cross-language tests. | [#870](https://github.com/apache/flink-agents/pull/870#discussion_r3592370999) |
| An explicitly configured null `structured_output_strategy` was normalized to `AUTO` on the Java side, while Python rejected `None` with a validation error. | Comparing the behavior each language derives from the same declarative value. | [#843](https://github.com/apache/flink-agents/pull/843#discussion_r3637512045) |
