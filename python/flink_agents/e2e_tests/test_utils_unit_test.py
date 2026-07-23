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
import json
from pathlib import Path

import pytest

from flink_agents.e2e_tests.test_utils import (
    assert_tool_invoked,
    collect_tool_invocations,
)


def _tool_request_record(tool_calls: list) -> dict:
    """Build a record matching the FileEventLogger wire format for a tool request."""
    return {
        "timestamp": "2026-05-31T00:00:00Z",
        "logLevel": "STANDARD",
        "event_id": "00000000-0000-0000-0000-000000000001",
        "event_type": "_tool_request_event",
        "event_attributes": {"model": "qwen3:1.7b", "tool_calls": tool_calls},
    }


def _function_tool_call(name: str, arguments: object) -> dict:
    return {
        "id": "00000000-0000-0000-0000-0000000000aa",
        "type": "function",
        "function": {"name": name, "arguments": arguments},
    }


def _write_log(log_dir: Path, records: list) -> None:
    log_dir.mkdir(parents=True, exist_ok=True)
    with (log_dir / "events-0.log").open("w") as handle:
        for record in records:
            handle.write(json.dumps(record) + "\n")


def test_collect_tool_invocations(tmp_path: Path) -> None:
    """Parser extracts nested function.name/function.arguments from a tool request line.

    Records with a different eventType are ignored.
    """
    log_dir = tmp_path / "event_logs"
    other_event = {
        "timestamp": "2026-05-31T00:00:00Z",
        "logLevel": "STANDARD",
        "event_id": "00000000-0000-0000-0000-000000000002",
        "event_type": "_input_event",
        "event_attributes": {},
    }
    _write_log(
        log_dir,
        [
            other_event,
            _tool_request_record([_function_tool_call("add", {"a": 1, "b": 2})]),
        ],
    )

    assert collect_tool_invocations(log_dir) == [
        {"name": "add", "arguments": {"a": 1, "b": 2}}
    ]


def test_collect_tool_invocations_no_tool(tmp_path: Path) -> None:
    """A run with no tool request event yields an empty list."""
    log_dir = tmp_path / "event_logs"
    _write_log(
        log_dir,
        [
            {
                "timestamp": "2026-05-31T00:00:00Z",
                "logLevel": "STANDARD",
                "event_id": "00000000-0000-0000-0000-000000000003",
                "event_type": "_output_event",
                "event_attributes": {},
            }
        ],
    )

    assert collect_tool_invocations(log_dir) == []


def test_collect_tool_invocations_legacy_format(tmp_path: Path) -> None:
    """Parser remains compatible with the old nested event log format."""
    log_dir = tmp_path / "event_logs"
    _write_log(
        log_dir,
        [
            {
                "timestamp": "2026-05-31T00:00:00Z",
                "logLevel": "STANDARD",
                "eventType": "_tool_request_event",
                "event": {
                    "eventType": "_tool_request_event",
                    "id": "00000000-0000-0000-0000-000000000001",
                    "attributes": {
                        "model": "qwen3:1.7b",
                        "tool_calls": [_function_tool_call("add", {"a": 1, "b": 2})],
                    },
                },
            }
        ],
    )

    assert collect_tool_invocations(log_dir) == [
        {"name": "add", "arguments": {"a": 1, "b": 2}}
    ]


def test_assert_tool_invoked_dict_args() -> None:
    """Passes when an invocation matches name and dict args (order-independent)."""
    invocations = [{"name": "add", "arguments": {"b": 2, "a": 1}}]
    assert_tool_invoked(invocations, "add", {"a": 1, "b": 2})


def test_assert_tool_invoked_json_string_args() -> None:
    """Passes when the recorded arguments are a JSON string rather than a dict."""
    invocations = [{"name": "add", "arguments": '{"a": 1, "b": 2}'}]
    assert_tool_invoked(invocations, "add", {"a": 1, "b": 2})


def test_assert_tool_invoked_none_args() -> None:
    """A no-arg tool call recorded as ``None`` matches an expected empty dict."""
    invocations = [{"name": "now", "arguments": None}]
    assert_tool_invoked(invocations, "now", {})


def test_assert_tool_invoked_mismatch_reports_invocations() -> None:
    """Raises AssertionError dumping the actual invocations on a mismatch."""
    invocations = [{"name": "add", "arguments": {"a": 9, "b": 9}}]
    with pytest.raises(AssertionError) as exc_info:
        assert_tool_invoked(invocations, "add", {"a": 1, "b": 2})
    assert "add" in str(exc_info.value)
    assert "9" in str(exc_info.value)
