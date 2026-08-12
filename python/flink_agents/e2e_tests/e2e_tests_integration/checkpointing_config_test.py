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
"""Guard for the checkpointing configuration the Flink e2e tests set."""

import re
from pathlib import Path

from pyflink.java_gateway import get_gateway

_E2E_TESTS_PACKAGE = Path(__file__).parents[1]

# Deliberately loose on setter name, stem, and quote style: a near miss Flink
# would silently ignore has to be caught whatever spelling it arrives in.
_CHECKPOINT_INTERVAL_KEY = re.compile(
    r"set_\w+\(\s*(?P<quote>['\"])"
    r"(?P<key>[^'\"]*checkpoint[^'\"]*interval[^'\"]*)(?P=quote)"
)


def test_checkpoint_interval_key_matches_the_flink_option() -> None:
    """Every checkpoint interval the e2e tests set uses the key Flink registers.

    Flink ignores an unregistered configuration key silently, with no exception
    and no log line, so a near miss such as ``checkpointing.interval`` leaves
    checkpointing disabled while the test still passes. Reading the expected key
    off ``CheckpointingOptions#CHECKPOINTING_INTERVAL`` takes it from Flink
    itself rather than from another hand-written copy of the string.
    """
    flink_config = get_gateway().jvm.org.apache.flink.configuration
    expected = flink_config.CheckpointingOptions.CHECKPOINTING_INTERVAL.key()

    found: dict[str, set[str]] = {}
    for path in sorted(_E2E_TESTS_PACKAGE.rglob("*_test.py")):
        for match in _CHECKPOINT_INTERVAL_KEY.finditer(path.read_text()):
            found.setdefault(match["key"], set()).add(path.name)

    assert found, "no checkpoint interval configured; the pattern above is stale"
    assert set(found) == {expected}, (
        f"checkpointing must be configured with {expected!r}, found "
        + "; ".join(
            f"{key!r} in {sorted(files)}" for key, files in sorted(found.items())
        )
    )
