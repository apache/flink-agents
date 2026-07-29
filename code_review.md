# Code Review Guide

This guide records repository-specific expectations for pull request reviews.
It is meant to be read before reviewing a PR.

## Review Posture

Prioritize correctness, runtime behavior, API design, compatibility policy,
regression risk, and missing tests. Findings should be concrete and tied to
files, lines, and call paths.

Flink Agents is beta. Breaking changes are acceptable when they remove unsafe
design, clarify contracts, or prevent old bug paths from remaining available.
Do not keep an API, fallback, or mutable state channel only for compatibility
when it conflicts with the new design.

## Review Lenses

Do not limit the review to bug-focused correctness checks. For non-trivial
changes, review through at least these lenses:

- Bug-focused review: check whether the change can fail at runtime, produce
  incorrect behavior, violate compatibility, miss required tests, or break
  documented contracts.
- Design-focused review: check whether the new or changed design is simple,
  coherent, maintainable, and aligned with module boundaries and user-facing
  concepts.

Keep these findings separate. A design-focused comment does not need to prove an
immediate runtime bug, but it must explain the concrete complexity, ambiguity,
or maintenance risk introduced by the change.

## Change-Type Review Guides

For common change types, start from the focused guide below as an on-ramp, then
apply the full passes in this document. Each guide narrows the checklist to the
concerns that matter most for that area and links examples from past reviews.
Guides load on demand, so the general passes here stay short.

| Change type | Focus | Guide |
|---|---|---|
| `runtime/` state and recovery | serde and replay type fidelity, real failure-path tests | [review-guides/runtime-state-recovery.md](review-guides/runtime-state-recovery.md) |
| Python-Java bridge | cross-language parity, type mapping across Pemja | planned |
| `api/` contract | API shape, compatibility policy, deprecation | planned |
| `dist` and dependency | shading, LICENSE and NOTICE, dist registration | planned |
| docs-only | facts match their source of truth | planned |

## Required Review Passes

1. Understand the issue or feature goal.
2. Verify that the implementation matches the PR's stated goal. Trace the goal
   to the changed code, tests, docs, and runtime behavior, and call out gaps
   where the PR only partially addresses the goal or validates an adjacent
   behavior instead of the intended contract.
3. For GitHub PRs, read existing unresolved human review threads before
   finalizing findings.
4. Read the diff, then trace the full runtime call path outside the diff.
5. Search all call sites of changed public, protected, and cross-language APIs.
6. Decide whether changed APIs should be kept, documented, deprecated, or
   deleted.
7. Run a design-focused pass for non-trivial changes. Look for unclear API
   shape, ambiguous contracts, unnecessary lifecycle state, duplicated
   abstractions, implementation details leaking into user-facing names or docs,
   avoidable fallback paths, and module-boundary violations.
8. Check user-facing names, docs, and error messages for implementation leaks,
   stale comments, or ambiguous contracts.
9. Check whether the root cause channel still exists through old APIs,
   defaults, fallback behavior, or mutable shared state.
10. Compare equivalent Java, Python, and YAML-facing behavior.
11. Check cross-language wrappers, adapters, serializers, and runtime bridge
   code when the change crosses Java/Python boundaries.
12. Check dependency, license, NOTICE, and shared-fixture changes when new
    libraries or generated/bundled artifacts are introduced.
13. Verify tests cover the behavior at the level where the bug happened.
14. Run targeted tests or clearly state why they could not be run.
15. Separate findings into blockers, non-blocking design or compatibility risks,
    and test gaps. Include relevant human-raised issues in the conclusion,
    marked as already raised by a human reviewer.

## Root-Cause Checklist

For bugfix PRs, ask:

- What exact state, call ordering, or contract caused the bug?
- Does the PR remove that cause, or only avoid reading it in one code path?
- After the fix, can any remaining entry point, overload, default value, or
  fallback still reach the old unsafe behavior?
- If the PR adds or changes a configuration option, is the documented behavior
  honored everywhere that option can affect runtime behavior, including default
  values and code that runs before the main action or evaluator?
- Are tests exercising the original failure mode, not just a lower-level helper?

## API And Documentation Clarity

For public APIs, configuration, and common runtime extension points, ask:

- Do names and error messages describe user-facing behavior instead of exposing
  implementation details?
- Does a doc comment describe the final contract, not old implementation
  history, generated rationale, or a not-yet-existing parallel implementation?
- Do changed user inputs reject values that were previously valid? If so, is the
  incompatibility intentional, documented, and covered by tests?
- Are invalid user inputs rejected with clear errors that name the invalid value
  and the expected format or contract?
- Are internal terms kept inside implementation classes unless callers need
  those concepts to use the API correctly?

## Beta Breaking-Change Policy

Because the project is beta, reviewers should actively recommend deleting
unsafe or misleading APIs when doing so simplifies the contract.
Do not preserve compatibility by default. Compatibility paths must have a
concrete obligation; otherwise prefer removing or rejecting them, especially
when they keep old bugs or ambiguous contracts alive.

When reviewing a public API change:

- search internal production call sites and tests separately
- identify external compatibility risk, but do not assume it blocks the change
- prefer one safe contract over two partially compatible contracts
- avoid keeping convenience overloads that encourage the old unsafe behavior
- ask whether tests should move from old APIs to the new intended API

## Java, Python, And YAML Parity

For equivalent Java/Python APIs and YAML-facing resource declarations, compare:

- null and `None` semantics
- default arguments
- no-op behavior
- exception behavior
- metric path and group naming
- sync, async, retry, and recovery paths
- tool-call and follow-up request paths
- resource names, YAML aliases/specs, and examples

Do not accept semantically different contracts unless the difference is
intentional and documented.

## Test Expectations

Tests should match the risk and blast radius.

For any behavior change:

- when a config option changes runtime behavior, test it through the production
  config path, including the default behavior
- add an end-to-end or near end-to-end test when a runtime routing or operator
  behavior changes

For bugfixes:

- add a regression test that fails on the old behavior when practical
- test the actual action/runtime path where the bug appeared
- avoid relying only on helper-method tests
- cover both Java and Python paths when the feature exists in both
- include async, retry, delayed callback, tool-call, or recovery paths when
  they affect the behavior

For API cleanup:

- update tests to use the intended API
- remove tests that only preserve deleted compatibility paths
- add contract tests for null/`None` or default behavior when relevant

## Dependencies And Shared Fixtures

When a PR adds libraries or shared test data, ask:

- Are LICENSE and NOTICE updated for bundled or shaded dependencies, including
  transitives?
- If a module is added to `dist` or a dependency is bundled/shaded, does the
  dist dependency tree match NOTICE and bundled license files?
- When adding a public integration or resource, are module registration, dist
  registration, resource names, YAML aliases, docs/examples, and wrappers
  updated or intentionally omitted?
- Are shared test fixtures placed where every consuming module can depend on
  them without violating module boundaries?

## Verification

Before claiming a review is complete, run focused verification commands when
possible. Report the exact commands and outcomes.

If local workspace changes unrelated to the PR block tests, do not edit or
revert them just to force a run. Either use a clean export/worktree or report
the blocker clearly.

If a command fails because of local environment restrictions, identify whether
the failure is from the PR, the workspace, missing dependencies, or sandboxing.
