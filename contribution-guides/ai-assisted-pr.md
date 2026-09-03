# Writing an Implementation Description for an AI-assisted PR

Read this before opening a pull request whose implementation is largely
AI-assisted. An Implementation Description is an as-built account of the
code the PR actually contains: what it does today, not what it was planned
to do. It is not a plan and not a spec, and it is written from the finished
diff. The tooling that produced the change already worked out the runtime
flow, the contracts and the failure paths; a plain summary throws all of
that away and leaves the reviewer to rebuild it from the diff. This guide
is iterative and will be extended as worked examples accumulate.

## When this applies

A non-trivial code change whose implementation is largely AI-assisted: a
change that adds or alters behavior a caller can observe, or that touches a
runtime path, a public API, or a failure path.

It does not apply to a change whose diff is already prose, such as
documentation, comments, or site content. The format is built around
runtime flow, behavioral contracts and failure behavior, and a prose diff
has none of them. Describing one produces a second copy of the change that
the reviewer then has to keep consistent with the first. Use the repository
template as it stands, and remove its `Behavioral Semantics` heading.

When only part of a PR qualifies, apply the seven fields to that part
alone, and say in one line which part of the diff they cover, so a reviewer
does not read the omission as a gap. Describe the rest of the diff through
the repository template as usual.

## The two-stage review it feeds

Stage one is a human, reviewing the architecture, the behavioral contracts,
the failure behavior and the compatibility impact as the description states
them. That stage is accountable for the judgment: whether these are the
right contracts, whether the failure behavior is acceptable, whether the
compatibility cost is worth paying.

Stage two is the checking agent, an agent that did not write the change. It
checks two directions: that the code and tests match the description claim
by claim, and that the description omits nothing important, by walking each
hunk of the diff and asking which field accounts for it. That stage is
accountable for the correspondence between the description and the code,
not for the design.

## Where it goes

The PR body is the description. There is no separate document, so the
description lives in exactly one place. Fold the seven fields into the
sections of `.github/PULL_REQUEST_TEMPLATE.md`; sections not listed here
are unchanged.

| Template section | Carries |
|---|---|
| `Purpose of change` | the user-visible outcome first, then the intent behind the change, then runtime flow and key decisions |
| `Behavioral Semantics` | interaction decisions, behavioral contracts, failure behavior |
| `Tests` | the contracts-to-tests table |
| `API` | compatibility impact |

`Behavioral Semantics` is the one section that exists for this format; the
other four fields fold into sections the template already had.
`Implementation Description` names the whole seven-field account, never one
section of it. Do not restructure the rest of the template. Where a section
carries more than one field, give each field its own `####` sub-heading so
a reader can find it at a glance; a section carrying a single field needs
none.

Open `Purpose of change` with what changes for a caller, then say why the
change is being made: the problem it solves and the intent behind the
approach taken. A reviewer who has to infer that from the diff is doing the
work the description exists to save.

Edit the body in place as review changes the code. It is the living record
of what the PR is, so a body describing the first revision is worse than
none.

## What it covers

Seven fields. Every one appears, or its absence is stated explicitly: a
missing section is invisible, while an explicit "none" is checkable and can
be shown wrong. Runtime flow varies in depth rather than in presence, a
single-path change needing a sentence and a cross-cutting one the whole
path. Interaction decisions may be stated as not applicable when the change
crosses no independent conditions.

**Runtime flow.** The execution path through the change, in order: which
component decides what, and where each decision is read. The flow, not the
reasoning behind it. A diagram is optional and can carry a cross-cutting
path better than prose; draw it as a Mermaid fence or in ASCII, so its
source sits in the pull request rather than in an uploaded image.

**Key decisions.** The non-obvious choices, and the alternatives rejected
with the reason.

**Interaction decisions.** A table crossing the independent conditions the
change combines, one row per meaningful combination, with the resulting
behavior in the cell. It exists because per-path contracts hide the places
two paths meet: two individually true contracts can compose into a pair
that cannot both hold, and a table forces the composition that prose lets
you skip. Where a combination is impossible, say so in the cell; one that
turns out to be reachable is a defect the table surfaces for free.

**Behavioral contracts.** What the code guarantees a caller, each one a
separate checkable claim. Two behaviors that can fail differently are two
contracts, because a reviewer can confirm only half of a merged claim. A
contract belongs here only if it is user-observable. The admission test:
could a caller write code that behaves differently if this were false? If
no, it is an implementation invariant, and it belongs in the collapsed
`<details>` block described under Placement and length.

**Failure behavior.** Every failure path, and for each one whether it
raises, falls back, retries, or is silently absorbed. Cover, where they
apply, an invalid or unsupported configuration, an error from an external
service, a response that violates an expected shape, and any case that
raises rather than degrading.

**Compatibility impact.** What changes for an existing caller, including
one who does nothing differently. Name what does not change as well as what
does.

**Contracts to tests.** A table with one row per behavioral contract,
listing the tests that pin it. One row per contract, not one per test: that
is what makes a contract with no test visible as an empty cell beside
filled ones, and such a contract gets a row saying so rather than being
dropped. A roster of test names is not coverage. Alongside the table,
summarize coverage by risk and give an explicit list of what was not
verified, meaning the behavior no test exercises and the conditions left
unchecked.

## Placement and length

The body has two readers with different budgets. A human decides where to
look; the checking agent verifies claims one by one. The question for any
fact is which reader it is for; what is dead weight to the first is the
evidence the second needs, so it is collapsed rather than cut.

The visible body carries the user-visible outcome, runtime flow, key
decisions, interaction decisions, behavioral contracts, failure behavior,
compatibility impact, the contracts-to-tests table, and coverage summarized
by risk together with the list of what was not verified. Put that
not-verified list before the collapsed block, because that is where a
reviewer looking for gaps stops reading.

One `<details>` block, at the end of `Tests`, carries the implementation
invariants demoted out of behavioral contracts, together with the
supporting evidence behind claims the visible body states in one line. That
is the detail the checking agent reads and a human scrolls past. Use one
block rather than one per section, and give it a summary line naming what
is inside, so a reader can tell whether to open it without opening it.

Target 6,000 characters or fewer for the visible body, the part outside the
`<details>` block. The target is absolute and does not scale with the size
of the diff. One to two screens. A reviewer should be able to decide how to
review without clicking anything. A `<details>` block relocates detail and
is not headroom; if the collapsed material grows past a screen of its own,
move that supporting evidence into a single PR comment and link to it from
the body. The seven fields stay in the body either way; only the evidence
behind them moves.

## What not to write

No process provenance. The description states what the code does and what
was decided, never how the author established it. That rules out discarded
drafts, corrected earlier claims, per-fact accounts of how something was
checked, and any narration of the workflow that produced the code. Keep a
rejected alternative only where a reviewer would otherwise propose it. Do
not cite issue or pull request numbers as the justification for a design
choice, and do not name reviewers.

## Worked examples

Two merged pull requests whose bodies use this format. Each is cited for
the part it shows, and an uncited field may be absent rather than merely
unremarkable; read them for shape rather than as templates to copy.

| Pull request | What it shows |
|---|---|
| [#952](https://github.com/apache/flink-agents/pull/952) | The fields folded into the template's sections on a small change: runtime flow and key decisions under `Purpose of change`, then behavioral contracts and failure behavior grouped under a single heading of their own. |
| [#965](https://github.com/apache/flink-agents/pull/965) | An interaction table crossing the independent conditions the change combines, on a large, two-language change, and implementation detail relocated into a `<details>` block at the end of `Tests`. |
