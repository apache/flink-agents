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
"""Assert the coding-agent skill still bundles the current YAML schema.

The skill ships a copy of docs/yaml-schema.json so it can answer YAML questions
offline, and records in yaml-contracts.yaml the git blob SHA that copy was taken
from. Nothing regenerates either one, so both drift silently the moment the
schema is re-exported. A stale copy teaches agents a schema the repository no
longer has, and a stale blob SHA misreports which revision the copy describes.

Only the unversioned "main" contract is checked. The versioned schemas beside it
pin released refs, so they are expected to differ from the working tree.

Regex-only and stdlib-only, hashing through git, so it runs on any Python 3
without a build step.
"""

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_SCHEMA = REPO_ROOT / "docs" / "yaml-schema.json"
ASSETS = REPO_ROOT / "dev" / "agent-skills" / "flink-agents-dev" / "assets"
BUNDLED_SCHEMA = ASSETS / "yaml-schema.json"
MANIFEST = ASSETS / "yaml-contracts.yaml"


def blob_sha(path: Path) -> str:
    """Return the blob SHA git would store for a file.

    Hashing the worktree bytes directly is only correct where no clean filter
    applies. Under core.autocrlf these JSON files are checked out CRLF, and
    their hash then never matches the LF blob SHA recorded in the manifest, so
    the check reports a current pin as stale and prescribes a SHA git would not
    store. Delegating to git applies whatever filter the path's attributes
    select.
    """
    try:
        result = subprocess.run(
            ["git", "hash-object", "--", str(path)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        sys.exit("error: git is required to hash the schema files, but is not on PATH")
    if result.returncode != 0:
        sys.exit(
            f"error: git hash-object failed for {path.relative_to(REPO_ROOT)}: "
            f"{result.stderr.strip()}"
        )
    return result.stdout.strip()


def recorded_sha(manifest: str) -> str:
    """Return the blob SHA the manifest's "main" contract was copied from."""
    # Bounded to the "main" block so a sibling contract's SHA cannot be read by
    # mistake when the main entry loses its own.
    block = re.search(r"^  main:\n((?:    .*\n|\n)*)", manifest, re.MULTILINE)
    if not block:
        sys.exit(f"error: no 'main' contract in {MANIFEST.relative_to(REPO_ROOT)}")
    match = re.search(r"^\s+blob_sha:\s*([0-9a-f]{40})\s*$", block.group(1), re.MULTILINE)
    if not match:
        sys.exit(f"error: 'main' contract has no blob_sha in {MANIFEST.relative_to(REPO_ROOT)}")
    return match.group(1)


def main() -> int:
    for path in (DOCS_SCHEMA, BUNDLED_SCHEMA, MANIFEST):
        if not path.is_file():
            sys.exit(f"error: {path.relative_to(REPO_ROOT)} does not exist")

    expected = blob_sha(DOCS_SCHEMA)
    bundled = blob_sha(BUNDLED_SCHEMA)
    recorded = recorded_sha(MANIFEST.read_text(encoding="utf-8"))

    failures = []
    if bundled != expected:
        failures.append(
            f"{BUNDLED_SCHEMA.relative_to(REPO_ROOT)} is {bundled}, expected {expected}"
        )
    if recorded != expected:
        failures.append(
            f"{MANIFEST.relative_to(REPO_ROOT)} records {recorded}, expected {expected}"
        )

    if failures:
        print("The bundled YAML schema is out of sync with docs/yaml-schema.json:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        print(
            f"\nRefresh both:\n"
            f"  cp {DOCS_SCHEMA.relative_to(REPO_ROOT)} {BUNDLED_SCHEMA.relative_to(REPO_ROOT)}\n"
            f"  # then set blob_sha: {expected} under the 'main' contract in\n"
            f"  # {MANIFEST.relative_to(REPO_ROOT)}",
            file=sys.stderr,
        )
        return 1

    print(f"Bundled YAML schema matches docs/yaml-schema.json ({expected}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
