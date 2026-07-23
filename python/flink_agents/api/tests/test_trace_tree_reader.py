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

_REPO_ROOT = Path(__file__).resolve().parents[4]
_READER = _REPO_ROOT / "tools" / "reconstruct_trace_tree.py"


def _record(
    event_id: str,
    event_type: str,
    upstream_event_id: str | None = None,
    upstream_action_name: str | None = None,
) -> dict:
    event = {"id": event_id, "eventType": event_type, "attributes": {}}
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
        [sys.executable, str(_READER), str(log_path), "--format", output_format],
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


def test_reader_warns_and_keeps_valid_input_tree(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("valid-child", "ChildEvent", "root", "valid_action"),
            _record("unlinked", "UnlinkedEvent"),
            _record("missing", "MissingEvent", "absent", "missing_action"),
            _record("duplicate", "FirstDuplicate"),
            _record("duplicate", "SecondDuplicate", "root", "duplicate_action"),
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
    assert "duplicate" not in trace_forest["nodes"]
    assert warning_codes == {
        "DUPLICATE_EVENT_ID",
        "MISSING_PARENT",
        "UNLINKED_EVENT",
    }
    assert "DUPLICATE_EVENT_ID" in result.stderr
    assert "MISSING_PARENT" in result.stderr
    assert "UNLINKED_EVENT" in result.stderr
