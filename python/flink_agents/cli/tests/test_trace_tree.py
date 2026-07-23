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
import subprocess
import sys
from pathlib import Path

import pytest

from flink_agents.cli.trace_tree import find_log_files


def _record(
    event_id: str,
    event_type: str,
    upstream_event_id: str | None = None,
    upstream_action_name: str | None = None,
    attributes: dict | None = None,
) -> dict:
    event = {
        "id": event_id,
        "eventType": event_type,
        "attributes": attributes or {},
    }
    if upstream_event_id is not None:
        event["upstreamEventId"] = upstream_event_id
    if upstream_action_name is not None:
        event["upstreamActionName"] = upstream_action_name
    return {
        "timestamp": "2026-07-17T10:00:00Z",
        "eventType": event_type,
        "event": event,
    }


def _write_log(path: Path, records: list[dict]) -> None:
    path.write_text("".join(json.dumps(record) + "\n" for record in records))


def _write_pretty_log(path: Path, records: list[dict]) -> None:
    path.write_text("".join(json.dumps(record, indent=2) + "\n" for record in records))


def _run_reader(log_path: Path, output_format: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            "-m",
            "flink_agents.cli.trace_tree",
            str(log_path),
            "--format",
            output_format,
        ],
        check=True,
        capture_output=True,
        text=True,
    )


def test_reader_reconstructs_text_and_json_in_log_order(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("child-1", "ChildEvent", "root", "branch_action"),
            _record("child-2", "ChildEvent", "root", "branch_action"),
            _record("output", "_output_event", "child-1", "output_action"),
            _record("child-3", "ChildEvent", "root", "second_action"),
        ],
    )

    json_result = _run_reader(log_path, "json")
    trace_forest = json.loads(json_result.stdout)
    text_result = _run_reader(log_path, "text")

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "branch_action", "children": ["child-1", "child-2"]},
        {"name": "second_action", "children": ["child-3"]},
    ]
    assert trace_forest["nodes"]["child-1"]["actions"] == [
        {"name": "output_action", "children": ["output"]}
    ]
    assert trace_forest["warnings"] == []
    assert json_result.stderr == ""
    expected_text_order = [
        "_input_event (root)",
        "[Action: branch_action]",
        "ChildEvent (child-1)",
        "[Action: output_action]",
        "_output_event (output)",
        "ChildEvent (child-2)",
        "[Action: second_action]",
        "ChildEvent (child-3)",
    ]
    positions = [text_result.stdout.index(item) for item in expected_text_order]
    assert positions == sorted(positions)
    assert text_result.stderr == ""


def test_reader_reconstructs_pretty_printed_records(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_pretty_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("child-1", "ChildEvent", "root", "branch_action"),
            _record("child-2", "ChildEvent", "root", "branch_action"),
        ],
    )

    json_result = _run_reader(log_path, "json")
    trace_forest = json.loads(json_result.stdout)
    text_result = _run_reader(log_path, "text")

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "branch_action", "children": ["child-1", "child-2"]}
    ]
    assert trace_forest["warnings"] == []
    assert "_input_event (root)" in text_result.stdout
    assert "[Action: branch_action]" in text_result.stdout
    assert text_result.stdout.index("ChildEvent (child-1)") < text_result.stdout.index(
        "ChildEvent (child-2)"
    )
    assert json_result.stderr == ""
    assert text_result.stderr == ""


def test_reader_warns_on_truncated_tail_and_keeps_valid_records(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    root = _record("root", "_input_event")
    child = _record("child", "ChildEvent", "root", "child_action")
    log_path.write_text(
        json.dumps(root) + "\n" + json.dumps(child) + '\n{"event":',
        encoding="utf-8",
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "child_action", "children": ["child"]}
    ]
    assert trace_forest["warnings"] == [
        {
            "code": "MALFORMED_RECORD",
            "message": (
                f"Could not decode an Event Log record in {log_path} "
                "at line 3, column 10: Expecting value."
            ),
            "filePath": str(log_path),
            "lineNumber": 3,
            "columnNumber": 10,
        }
    ]
    assert "[MALFORMED_RECORD]" in result.stderr


def test_reader_resynchronizes_after_malformed_pretty_record(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    root = _record("root", "_input_event")
    child = _record("child", "ChildEvent", "root", "child_action")
    log_path.write_text(
        json.dumps(root, indent=2)
        + "\n"
        + '{"event": invalid}\n'
        + json.dumps(child, indent=2)
        + "\n",
        encoding="utf-8",
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "child_action", "children": ["child"]}
    ]
    assert [item["code"] for item in trace_forest["warnings"]] == ["MALFORMED_RECORD"]
    assert "[MALFORMED_RECORD]" in result.stderr


@pytest.mark.parametrize(
    ("malformed_record", "expected_event_id"),
    [
        ([], None),
        ({}, None),
        ({"eventType": "BadEvent", "event": []}, None),
        ({"eventType": "BadEvent", "event": {"attributes": {}}}, None),
        ({"eventType": 123, "event": {"id": "bad"}}, None),
        (
            {
                "eventType": "BadEvent",
                "event": {"id": "bad", "upstreamEventId": []},
            },
            "bad",
        ),
    ],
)
def test_reader_warns_on_invalid_record_shape_and_continues(
    tmp_path: Path, malformed_record: object, expected_event_id: str | None
) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            malformed_record,
            _record("child", "ChildEvent", "root", "child_action"),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "child_action", "children": ["child"]}
    ]
    assert [item["code"] for item in trace_forest["warnings"]] == ["MALFORMED_RECORD"]
    malformed_warning = trace_forest["warnings"][0]
    assert malformed_warning["filePath"] == str(log_path)
    assert malformed_warning["message"].startswith(
        f"Invalid Event Log record in {log_path}: "
    )
    assert "lineNumber" not in malformed_warning
    assert "columnNumber" not in malformed_warning
    if expected_event_id is None:
        assert "eventId" not in malformed_warning
    else:
        assert malformed_warning["eventId"] == expected_event_id


def test_directory_discovery_ignores_unrelated_log_files(tmp_path: Path) -> None:
    unrelated_log = tmp_path / "taskmanager.log"
    unrelated_log.write_text("not an Event Log", encoding="utf-8")

    assert find_log_files(tmp_path) == []


def test_reader_warns_on_unreadable_file_and_keeps_other_files(
    tmp_path: Path,
) -> None:
    unreadable_log = tmp_path / "events-1-unreadable.log"
    valid_log = tmp_path / "events-2-valid.log"
    unreadable_log.write_bytes(b"\xff")
    _write_log(valid_log, [_record("root", "_input_event")])

    result = _run_reader(tmp_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["warnings"] == [
        {
            "code": "UNREADABLE_FILE",
            "message": f"Could not read Event Log file {unreadable_log}.",
            "filePath": str(unreadable_log),
        }
    ]
    assert "[UNREADABLE_FILE]" in result.stderr


def test_reader_models_reused_event_as_dag_and_deduplicates_edges(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    reused_record = _record(
        "shared-output",
        "SharedEvent",
        "root-1",
        "shared_action",
        {"value": 1},
    )
    _write_log(
        log_path,
        [
            _record("root-1", "_input_event"),
            reused_record,
            reused_record,
            _record("root-2", "_input_event"),
            _record(
                "shared-output",
                "SharedEvent",
                "root-2",
                "shared_action",
                {"value": 1},
            ),
        ],
    )

    json_result = _run_reader(log_path, "json")
    trace_forest = json.loads(json_result.stdout)
    text_result = _run_reader(log_path, "text")

    assert trace_forest["roots"] == ["root-1", "root-2"]
    assert trace_forest["nodes"]["shared-output"]["observationCount"] == 3
    assert trace_forest["nodes"]["shared-output"]["upstreamEdges"] == [
        {
            "upstreamEventId": "root-1",
            "upstreamActionName": "shared_action",
        },
        {
            "upstreamEventId": "root-2",
            "upstreamActionName": "shared_action",
        },
    ]
    assert trace_forest["nodes"]["root-1"]["actions"] == [
        {"name": "shared_action", "children": ["shared-output"]}
    ]
    assert trace_forest["nodes"]["root-2"]["actions"] == [
        {"name": "shared_action", "children": ["shared-output"]}
    ]
    assert trace_forest["warnings"] == []
    assert text_result.stdout.count("SharedEvent (shared-output)") == 2
    assert json_result.stderr == ""
    assert text_result.stderr == ""


def test_text_reader_handles_trace_deeper_than_recursion_limit(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    depth = sys.getrecursionlimit() + 10
    records = [_record("event-0", "_input_event")]
    records.extend(
        _record(
            f"event-{index}",
            "MiddleEvent",
            f"event-{index - 1}",
            f"action-{index}",
        )
        for index in range(1, depth + 1)
    )
    _write_log(log_path, records)

    result = _run_reader(log_path, "text")

    assert f"MiddleEvent (event-{depth})" in result.stdout
    assert result.stderr == ""


def test_reader_compares_event_content_by_json_type(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record(
                "type-sensitive",
                "ChildEvent",
                "root",
                "child_action",
                {"value": True},
            ),
            _record(
                "type-sensitive",
                "ChildEvent",
                "root",
                "child_action",
                {"value": 1},
            ),
            _record(
                "equivalent",
                "ChildEvent",
                "root",
                "child_action",
                {"nested": {"first": 1, "second": 2}},
            ),
            _record(
                "equivalent",
                "ChildEvent",
                "root",
                "child_action",
                {"nested": {"second": 2, "first": 1}},
            ),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert "type-sensitive" not in trace_forest["nodes"]
    assert trace_forest["nodes"]["equivalent"]["observationCount"] == 2
    assert [item["code"] for item in trace_forest["warnings"]] == ["EVENT_ID_CONFLICT"]


def test_reader_warns_and_keeps_valid_input_tree(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("valid-child", "ChildEvent", "root", "valid_action"),
            _record("unlinked", "UnlinkedEvent"),
            _record("missing", "MissingEvent", "absent", "missing_action"),
            _record(
                "type-conflict",
                "FirstType",
                "root",
                "conflicting_action",
                {"value": 1},
            ),
            _record(
                "type-conflict",
                "SecondType",
                "root",
                "conflicting_action",
                {"value": 1},
            ),
            _record(
                "content-conflict",
                "ConflictingEvent",
                "root",
                "conflicting_action",
                {"value": 1},
            ),
            _record(
                "content-conflict",
                "ConflictingEvent",
                "root",
                "conflicting_action",
                {"value": 2},
            ),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)
    warning_codes = {warning["code"] for warning in trace_forest["warnings"]}

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "valid_action", "children": ["valid-child"]}
    ]
    assert "unlinked" in trace_forest["nodes"]
    assert "missing" in trace_forest["nodes"]
    assert "type-conflict" not in trace_forest["nodes"]
    assert "content-conflict" not in trace_forest["nodes"]
    assert warning_codes == {
        "EVENT_ID_CONFLICT",
        "MISSING_PARENT",
        "UNLINKED_EVENT",
    }
    assert result.stderr.count("EVENT_ID_CONFLICT") == 2
    assert "MISSING_PARENT" in result.stderr
    assert "UNLINKED_EVENT" in result.stderr
