# Review Guide: api/ Contract

Load this guide when a PR changes a public API surface: a signature or type in
`api/`, a new resource implementation, a config option, a YAML-visible name, or
anything a user's agent code calls. It narrows the full passes in
`code_review.md` to the ones that matter most for this area; the general passes
still apply.

## Focused checklist

- When a PR adds a public resource implementation, check that its short YAML
  alias landed in both alias tables and the doc table. Nothing fails when all
  three are skipped: each loader passes an unrecognized name through unchanged,
  so the class stays reachable by fully-qualified name and no test notices the
  omission.
- Regenerate the cross-language snapshots on both sides in the same change when
  a field on a built-in event or on the agent plan is added, renamed, or
  retyped. Each language pins its own serialization against its own committed
  file, so refreshing one side leaves the other side's stability test failing. A
  field added in only one language is caught by nothing, because the payload is
  a free-form attribute map on the read side.
- Check that a new public config option landed on both languages' sides. A
  Java-only option passes every fast CI job: the bidirectional parity check runs
  only in the slow cross-language lane, and the in-tree guard is a hardcoded
  count on the Python side.
- Treat the public base classes users extend as source-compatibility boundaries.
  A new abstract method breaks every implementation, including ones outside this
  repo, while a defaulted overload plus a capability probe does not. Compiling
  is not the same as honoring the new argument: a default that forwards to the
  older signature drops it in silence, so check that the default rejects what it
  cannot honor, and that an override gating it behind a capability probe leaves
  a fallback in force or fails, rather than silently doing neither. The Python
  guard that catches a mis-declared override only sees connections whose module
  is imported by hand at the top of the test.
- Check that a removal is complete rather than asking whether to deprecate.
  There is no deprecation mechanism in this repo, so an API is either kept or
  deleted outright. Under the beta policy, prefer deleting unless a concrete
  compatibility obligation requires keeping it.
- Name the docs the change invalidates. Config keys, YAML aliases, and whole
  code samples are restated by hand across the doc site, and nothing in pull
  request CI builds or checks them, so a doc that contradicts the code ships
  green. Java code under `examples/` is in the Maven reactor and breaks loudly,
  but nothing imports or runs the Python examples.

## Validation

Run both lanes. A change verified in one language only is untested in the other.

- Java, from the repo root: `mvn --batch-mode test -pl api`.
- Python, from `python/`: `uv sync --extra test`, then `uv pip install
  "apache-flink==$(mvn -q -N --batch-mode -f ../pom.xml help:evaluate
  -Dexpression=flink.version -DforceStdout)"`, then `uv run --no-sync pytest
  flink_agents/api flink_agents/plan`. PyFlink is imported at module scope by
  the event types but is declared in neither the base dependencies nor the
  `test` extra, so collection fails without it. Install it after the sync, not
  before, because `uv sync` removes it. Reading the version out of the root
  `pom.xml` rather than typing it runs this lane on the same Flink the Java
  bullet resolves, and survives a version bump.
- Resource-name constants, from `python/`: `uv run --no-sync python
  ../e2e-test/test-scripts/check_resource_consistency.py`. This one is a script
  rather than a test, so neither Maven nor pytest reaches it.

Two more when the change reaches further:

- Agent-plan wire format: `mvn --batch-mode test -pl plan -am
  -Dtest=AgentPlanCrossLanguageTest -Dsurefire.failIfNoSpecifiedTests=false`.
  The `-am` is what puts your edited `api` classes on the classpath in place of
  the last installed jar.
- A new or changed config option: `mvn --batch-mode package -pl api -DskipTests`,
  then from `python/`, `uv run --no-sync python
  flink_agents/plan/tests/compatibility/check_java_python_config_options_parity.py`.
  It loads the Java class out of `api/target/`, so an absent or stale jar is
  exactly what it reads.

## Examples from past reviews

| Case | Pass it exercises | Review |
|---|---|---|
| A new public reconciler contract gave a recovering call two ways out, both of them throws: a terminal business exception, or a fallback exception that re-runs the call. The review asked what a partially succeeded call is supposed to do, and proposed reducing the contract to returning a result or throwing. | Whether a new public contract's semantics fit its users, not only the implementation behind it. | [#600](https://github.com/apache/flink-agents/pull/600#discussion_r3027614878) |
| Renaming a persisted plan key left a deserializer fallback so plans written under the old name still loaded. The review argued the fallback was not worth keeping, since API compatibility is not guaranteed in the 0.x series and the formal stability commitment starts at 1.0. | Whether a compatibility path is justified under the beta policy. | [#756](https://github.com/apache/flink-agents/pull/756#discussion_r3392902728) |
