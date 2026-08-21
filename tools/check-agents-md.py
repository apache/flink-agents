#!/usr/bin/env python3
################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
"""Assert the version facts quoted in AGENTS.md still match their sources.

AGENTS.md hard-codes the supported Python range, the Ruff line length, and the
Java version. Those values live authoritatively in python/pyproject.toml and the
root pom.xml, so the prose drifts silently when they are bumped. This check
derives the expected phrasing from the sources and fails if AGENTS.md no longer
contains it, forcing the guide to be updated alongside the source of truth.

Regex-only and dependency-free so it runs on any Python 3 without a build step.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
AGENTS_MD = REPO_ROOT / "AGENTS.md"
PYPROJECT = REPO_ROOT / "python" / "pyproject.toml"
POM_XML = REPO_ROOT / "pom.xml"


def _search(pattern: str, text: str, source: Path) -> str:
    match = re.search(pattern, text)
    if not match:
        sys.exit(f"error: could not find {pattern!r} in {source}")
    return match.group(1)


def expected_phrases() -> list[tuple[str, str]]:
    """Return (phrase, source-description) pairs AGENTS.md must contain."""
    pyproject = PYPROJECT.read_text(encoding="utf-8")
    pom = POM_XML.read_text(encoding="utf-8")

    requires_python = _search(r'requires-python\s*=\s*"([^"]+)"', pyproject, PYPROJECT)
    lower = re.search(r">=\s*(\d+)\.(\d+)", requires_python)
    upper = re.search(r"<\s*(\d+)\.(\d+)", requires_python)
    if not lower or not upper:
        sys.exit(f"error: cannot parse requires-python {requires_python!r} in {PYPROJECT}")
    min_python = f"{lower.group(1)}.{lower.group(2)}"
    # Upper bound is exclusive, so the highest supported minor is one below it.
    max_python = f"{upper.group(1)}.{int(upper.group(2)) - 1}"

    line_length = _search(r"line-length\s*=\s*(\d+)", pyproject, PYPROJECT)
    java_version = _search(r"<target\.java\.version>(\d+)</target\.java\.version>", pom, POM_XML)

    return [
        (f"{min_python} through {max_python}", f"requires-python in {PYPROJECT.name}"),
        (f"{line_length}-character line length", f"ruff line-length in {PYPROJECT.name}"),
        (f"Java {java_version}", f"target.java.version in {POM_XML.name}"),
    ]


def main() -> int:
    agents_md = AGENTS_MD.read_text(encoding="utf-8")
    failures = [
        (phrase, source)
        for phrase, source in expected_phrases()
        if phrase not in agents_md
    ]
    if failures:
        print("AGENTS.md is stale relative to its sources:", file=sys.stderr)
        for phrase, source in failures:
            print(f"  - expected {phrase!r} (from {source})", file=sys.stderr)
        print(
            "\nUpdate AGENTS.md to match, or adjust this check if the fact moved.",
            file=sys.stderr,
        )
        return 1
    print("AGENTS.md version facts match their sources.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
