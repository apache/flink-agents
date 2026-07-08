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

## Required Review Passes

1. Understand the issue or feature goal.
2. For GitHub PRs, read existing unresolved human review threads before
   finalizing findings.
3. Read the diff, then trace the full runtime call path outside the diff.
4. Search all call sites of changed public, protected, and cross-language APIs.
5. Decide whether changed APIs should be kept, documented, deprecated, or
   deleted.
6. Check user-facing names, docs, and error messages for implementation leaks,
   stale comments, or ambiguous contracts.
7. Check whether the root cause channel still exists through old APIs,
   defaults, fallback behavior, or mutable shared state.
8. Compare equivalent Java, Python, and YAML-facing behavior.
9. Check cross-language wrappers, adapters, serializers, and runtime bridge
   code when the change crosses Java/Python boundaries.
10. Check dependency, license, NOTICE, and shared-fixture changes when new
    libraries or generated/bundled artifacts are introduced.
11. Verify tests cover the behavior at the level where the bug happened.
12. Run targeted tests or clearly state why they could not be run.
13. Separate findings into blockers, non-blocking risks, and test gaps. Include
    relevant human-raised issues in the conclusion, marked as already raised by
    a human reviewer.

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
