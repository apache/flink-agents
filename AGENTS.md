# Repository Guidelines

## Project Structure & Module Organization

Flink Agents is a mixed Java and Python project with aligned module concepts across both languages. The core modules are `api/`, `plan/`, and `runtime/`; preserve the `runtime -> plan -> api` dependency direction in both Java and Python, with no reverse dependencies. `integrations/` contains ecosystem integrations. `e2e-test/` and `python/flink_agents/e2e_tests/` contain end-to-end tests, including cross-language suites. Java code follows `src/main/java` and `src/test/java`; Python code lives under `python/flink_agents/`.

## Build, Test, and Development Commands

Use repository scripts as entry points, and read the script source or help output before choosing flags, Flink versions, or environment variables.

- `./tools/build.sh`: builds Java artifacts, distribution JARs, Python dependencies, and the wheel.
- `./tools/ut.sh`: runs Java, Python, and end-to-end test suites.
- `./tools/lint.sh`: formats or checks Python with Ruff and Java with Spotless.
- `./tools/check-license.sh`: verifies or fixes Apache license headers.

## Coding Style & Naming Conventions

Java targets Java 11 and is formatted by Spotless using google-java-format AOSP style. Use package paths under `org.apache.flink.agents.*`, `UpperCamelCase` classes, and test classes ending in `Test`. Python requires 3.10 through 3.12, uses Ruff with an 88-character line length, Google-style docstrings, absolute imports, and `snake_case` for modules, functions, and test files. Public API changes must keep Java, Python, and YAML APIs semantically aligned.

## Testing Guidelines

Java tests use JUnit 5 with AssertJ and Mockito. Python tests use pytest; integration tests are marked with `integration` and can require external services such as Ollama, OpenAI, Chroma, or mem0. Before Python-facing tests, including cross-language suites, export `PYTHONPATH=$(python3 -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')` or the equivalent path from the active Python environment. Prefer focused tests near the changed module, then run `./tools/ut.sh` or the relevant narrowed variant before opening a PR.

- If verification may be using stale generated, built, or installed artifacts, rebuild with `./tools/build.sh` or document an equivalent targeted rebuild before attributing the failure to source changes.

## Commit & Pull Request Guidelines

Use concise subjects with bracketed components, matching existing history, such as `[python] Admit bytes in memory values` or `[api][java] Add event constants`. For nontrivial behavior changes, open or link a GitHub issue before the PR. PR titles should include relevant components like `[api]`, `[runtime]`, `[java]`, `[python]`, or `[hotfix]`; describe the change, test evidence, and any compatibility impact.

## Code Review

Before reviewing a PR, read `code_review.md`. For GitHub PRs, read existing unresolved human review threads and include relevant already-raised issues in the local review conclusion, marked as human-raised. Apply its passes for runtime call paths, Java/Python parity, cross-language bridges, root-cause removal, and targeted verification.
