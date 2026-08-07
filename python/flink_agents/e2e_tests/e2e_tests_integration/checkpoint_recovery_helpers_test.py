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
"""Unit tests for the checkpoint-recovery helpers that decide pass or fail.

Covers the handshake's two obligations to the harness and the blob predicate the
verdict is computed from.
"""

import time
from pathlib import Path
from typing import Any

import pytest

from flink_agents.e2e_tests.e2e_tests_integration.checkpoint_recovery_agent import (
    _KNOWN_BLOB,
    RELEASE_MARKER,
    TOOL_ENTERED_MARKER,
    _blob_matches,
    await_release,
)


def test_release_present_checks_before_sleeping(tmp_path: Path) -> None:
    """Contract: the release marker is checked before any poll interval is paid.

    After a restore the tool re-enters the handshake with the marker already on
    disk. The poll interval used here is far larger than the assertion window, so
    an implementation that slept first would miss it.
    """
    (tmp_path / RELEASE_MARKER).touch()

    start = time.monotonic()
    await_release(str(tmp_path), timeout_s=30.0, poll_interval_s=5.0)

    assert time.monotonic() - start < 2.0


def test_tool_entered_marker_is_written(tmp_path: Path) -> None:
    """Contract: entering the handshake announces itself to the harness.

    The harness orders its kill after this marker appears, so a handshake that
    waited without announcing would leave the harness with nothing to wait on.
    """
    (tmp_path / RELEASE_MARKER).touch()

    await_release(str(tmp_path), timeout_s=30.0, poll_interval_s=5.0)

    assert (tmp_path / TOOL_ENTERED_MARKER).exists()


def test_deadline_raises(tmp_path: Path) -> None:
    """Contract: the handshake raises at its deadline instead of returning.

    The raise bounds the wait and puts the reason in the TaskManager log. It does
    not by itself fail the run, because the caller swallows tool exceptions; the
    tool result sentinel is what makes a timeout observable downstream.
    """
    with pytest.raises(TimeoutError):
        await_release(str(tmp_path), timeout_s=0.3, poll_interval_s=0.05)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (_KNOWN_BLOB, True),
        (bytearray(_KNOWN_BLOB), True),
        (b"other", False),
        (memoryview(_KNOWN_BLOB), False),
        (list(_KNOWN_BLOB), False),
        (tuple(_KNOWN_BLOB), False),
        ("string", False),
        (None, False),
        (5, False),
    ],
)
def test_blob_matches_compares_content_only(raw: Any, expected: bool) -> None:
    """Contract: ``bytes`` or ``bytearray`` holding the known content, nothing else.

    A list or tuple of the same ints must not pass: ``bytes()`` accepts any iterable
    of ints, so a sequence that merely enumerates the right byte values is not
    evidence the value survived as a byte array.

    ``memoryview`` is rejected deliberately, not by oversight. Admitting it would be
    speculation about how the bridge materializes a ``byte[]``, and it would also
    admit views whose element format is not a byte. If one ever does arrive this
    fails, and ``blob_observed_type`` in the verdict names what came back.
    """
    assert _blob_matches(raw) is expected
