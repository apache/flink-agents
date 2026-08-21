# Review Guide: Docs-Only Changes

Load this guide when a PR changes only documentation: pages under
`docs/content`, including the fenced code blocks inside them. It narrows the
full passes in `code_review.md` to the ones that matter most for this area; the
general passes still apply.

## Focused checklist

- Search the source for every identifier a page names. A method, class, module
  path, or index that a page states has to exist in the code that owns it, and
  a fenced block is where this drifts first, because nothing compiles, imports,
  or runs the code inside a page. A sibling page spelling the same call
  differently is a reliable sign that one of them is stale.
- Check a claim of support against the maturity the code records. A comment or
  Javadoc marking a path as experimental, smoke-tested, or a follow-up is the
  strongest claim the page can make about it. A test named as the proof of a
  guarantee has to be read for what it asserts rather than for what its name
  suggests.
- Re-derive every value a page restates from whatever owns it: a version
  literal, a configuration default, a supported-provider list. The owner
  usually sits in another module, so a page and its source can be edited in
  separate PRs and drift apart with nothing to catch it. Confirm which side is
  current rather than assuming the code is.
- Read a copy-paste sample as a reader outside this repository would. A
  version, property, or coordinate that resolves only inside this project's own
  build fails for everyone else, and the sample often already defines the
  properties that would have made it work.
- Check the front matter, not only the prose. `weight` orders a page among the
  pages it will sit beside, so reusing a value one of them already holds makes
  the intended order ambiguous.

## Validation

A change under `docs/content` runs the full CI matrix and passes it without
any job reading a changed line. `ci.yml` has no path filter, so every job
runs, but none of them read `docs/content`, and ruff runs with `python/` as
its root. The site build lives in a separate workflow triggered on a schedule
and by manual dispatch rather than on pull requests. Treat the green check as
unrelated to the change and verify by hand.

- Resolve each identifier a page asserts against the source that owns it:
  `grep -rn "<identifier>" --include='*.java' --include='*.py' .`
- Resolve each `{{< ref >}}` target to a file under `docs/content`, and where
  the target carries an anchor, to a heading in that file. No link checker runs
  anywhere in the repository.

## Examples from past reviews

| Case | Pass it exercises | Review |
|---|---|---|
| A page stated that collection metadata is kept in a dedicated index, naming it. The identifier appeared nowhere in the code, and the implementation's own Javadoc recorded the opposite, that it persists no collection-level metadata. | Whether an identifier a page names can be found in the code that owns it, and whether the implementation's own documentation agrees. | [#680](https://github.com/apache/flink-agents/pull/680#discussion_r3297930919) |
| A page presented a backend as supported, while a comment in the connection class recorded that path as wired and smoke-tested at construction with a full end-to-end run still a follow-up. | Whether a claim of support matches the maturity the code records. | [#898](https://github.com/apache/flink-agents/pull/898#discussion_r3585466817) |
