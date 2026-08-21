# Review Guide: dist and Dependency

Load this guide when a PR changes what the project ships or what it bundles:
the `dist` modules and their shade configuration, LICENSE or NOTICE, or a
dependency's coordinates, version, or scope in any module's `pom.xml`. It
narrows the full passes in `code_review.md` to the ones that matter most for
this area; the general passes still apply.

## Focused checklist

- Check that a module the change adds appears in `dist/pom.xml`. The shipped
  set is a hand-written list of dependencies rather than anything derived
  from the reactor, so a module missing from it can compile, test, and
  release green while shipping none of its classes.
- Re-derive the NOTICE from the dependency tree the change resolves to
  rather than from the dependencies it declares. A transitive addition
  changes what is bundled without appearing in the diff, and nothing in the
  build validates a NOTICE, so an entry that is absent or stale ships.
- Confirm a newly bundled dependency's version is declared rather than left
  to Maven to resolve. Nothing is relocated, so every bundled dependency
  shares one namespace, and the version one integration pulls in becomes the
  version the others get.
- Treat a version change, or a move into or out of provided or test scope,
  as a change to what ships, including when it arrives as cleanup unrelated
  to the stated goal. Shading bundles compile and runtime alike, so moving
  between those two changes nothing, while provided and test move a
  dependency out of the artifact or back into it, which carries both a
  NOTICE consequence and a deployment one.
- Read a shade filter for what it matches rather than for what it was
  written to match. An exclude placed inside a filter whose artifact pattern
  matches everything applies to every artifact being shaded, so a class
  meant to be dropped from one conflicting dependency takes the
  distribution's own copy with it.
- Compare a new file's license header against a complete one. The automated
  check keys on the Apache License URL, so a header that keeps that URL
  passes with prose lines dropped or altered, and the configuration that
  would compare header text against a template runs in no workflow.

## Validation

No job compares what an artifact contains against what the NOTICE claims,
and the license check reads for a header's presence rather than its text, so
a green build says nothing about either. Build the artifact and read it.

- Build one distribution: `mvn --batch-mode package -pl dist/flink-<version>
  -am -DskipTests`. Use `package` rather than `install`, which writes
  distribution jars into your local repository.
- List what shipped: `jar tf dist/flink-<version>/target/<artifact>.jar`. The
  fat jar carries the bundled dependencies and the NOTICE, while the `-thin`
  variant carries this project's own classes alone and holds no NOTICE.
- See which version won: `mvn --batch-mode dependency:tree -Dverbose -pl
  dist/common`. Without `-Dverbose` the output shows the resolved tree with
  no indication of what was mediated away.
- License headers: `./tools/check-license.sh`. It reports presence, not
  correctness.

## Examples from past reviews

| Case | Pass it exercises | Review |
|---|---|---|
| A new shade execution produced a thin jar for the Python wheel and placed a single-class exclude inside a filter whose artifact pattern matched every artifact. One distribution supplies its own version of that class, so it would have shipped without it. | Whether a shade filter matches only the artifact it was written for. | [#557](https://github.com/apache/flink-agents/pull/557#discussion_r2875661184) |
| After a rebase the NOTICE no longer matched the tree the distribution resolved: a bundled version was declared at an older number than the one that resolved, and three transitive dependencies were absent. The review also asked that the resolved version be managed explicitly rather than left to dependency mediation, since it was winning over the versions two other integrations requested. | Whether the NOTICE matches the resolved dependency tree, and whether a bundled version is declared rather than implicitly chosen. | [#821](https://github.com/apache/flink-agents/pull/821#discussion_r3719020558) |
