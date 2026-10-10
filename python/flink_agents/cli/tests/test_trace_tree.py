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
    metadata = {"eventId": event_id}
    if upstream_event_id is not None:
        metadata["upstreamEventId"] = upstream_event_id
    if upstream_action_name is not None:
        metadata["upstreamActionName"] = upstream_action_name
    record = {
        "timestamp": "2026-07-17T10:00:00Z",
        "entityType": "event",
        "entityName": event_type,
        "entityMetadata": metadata,
        "attributes": attributes or {},
    }
    return record


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
    assert all("children" not in node for node in trace_forest["nodes"].values())
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


def test_reader_links_action_generated_input(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("new-input", "_input_event", "root", "send_input"),
            _record("output", "_output_event", "new-input", "process_input"),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "send_input", "children": ["new-input"]}
    ]
    assert trace_forest["nodes"]["new-input"]["actions"] == [
        {"name": "process_input", "children": ["output"]}
    ]
    assert trace_forest["nodes"]["new-input"]["upstreamEdges"] == [
        {"upstreamEventId": "root", "upstreamActionName": "send_input"}
    ]
    assert trace_forest["warnings"] == []
    assert result.stderr == ""

    text_result = _run_reader(log_path, "text")
    expected_text_order = [
        "_input_event (root)",
        "[Action: send_input]",
        "_input_event (new-input)",
        "[Action: process_input]",
        "_output_event (output)",
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


def test_reader_rejects_old_flat_and_nested_record_formats(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            {"eventId": "flat", "eventType": "OldEvent", "eventAttributes": {}},
            {"eventType": "OldEvent", "event": {"id": "nested", "attributes": {}}},
            _record("child", "ChildEvent", "root", "child_action"),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "child_action", "children": ["child"]}
    ]
    assert set(trace_forest["nodes"]) == {"root", "child"}
    assert [item["code"] for item in trace_forest["warnings"]] == [
        "MALFORMED_RECORD",
        "MALFORMED_RECORD",
    ]
    assert result.stderr.count("MALFORMED_RECORD") == 2


def test_reader_ignores_non_event_entities(tmp_path: Path) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            *[
                {
                    "entityType": entity_type,
                    "entityName": "component",
                    "executionId": f"execution-{entity_type}",
                    "status": "success",
                    "attributes": {"output": "value"},
                }
                for entity_type in ("action", "llm", "tool", "parser")
            ],
            _record("child", "ChildEvent", "root", "child_action"),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert set(trace_forest["nodes"]) == {"root", "child"}
    assert trace_forest["warnings"] == []
    assert result.stderr == ""


def test_reader_treats_lifecycle_names_as_ordinary_event_names(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    reserved_type = "_execution_started_event"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("reserved", reserved_type, "root", "reserved_action"),
            _record("child", "ChildEvent", "reserved", "child_action"),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert set(trace_forest["nodes"]) == {"root", "reserved", "child"}
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "reserved_action", "children": ["reserved"]}
    ]
    assert trace_forest["nodes"]["reserved"]["actions"] == [
        {"name": "child_action", "children": ["child"]}
    ]
    assert trace_forest["warnings"] == []
    assert result.stderr == ""


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
                f"Could not decode a Trace Log record in {log_path} "
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
        ({**_record("bad", "BadEvent"), "entityName": 123}, None),
        ({**_record("bad", "BadEvent"), "entityMetadata": []}, None),
        ({**_record("bad", "BadEvent"), "entityMetadata": {}}, None),
        ({**_record("bad", "BadEvent"), "attributes": []}, "bad"),
        (
            {
                **_record("bad", "BadEvent"),
                "entityMetadata": {"eventId": "bad", "upstreamEventId": []},
            },
            "bad",
        ),
        (
            {
                **_record("bad", "BadEvent"),
                "entityMetadata": {"eventId": "bad", "upstreamActionName": 123},
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
        f"Invalid Trace Log record in {log_path}: "
    )
    assert "lineNumber" not in malformed_warning
    assert "columnNumber" not in malformed_warning
    if expected_event_id is None:
        assert "eventId" not in malformed_warning
    else:
        assert malformed_warning["eventId"] == expected_event_id


def test_directory_discovery_finds_backend_logs_without_duplicates(
    tmp_path: Path,
) -> None:
    unrelated_log = tmp_path / "taskmanager.log"
    unrelated_log.write_text("not a Trace Log", encoding="utf-8")

    assert find_log_files(tmp_path) == []

    expected_names = [
        "jobmanager.trace-log.log",
        "traces-1.log",
        "traces-2.trace-log.log",
    ]
    for name in reversed(expected_names):
        (tmp_path / name).touch()

    assert find_log_files(tmp_path) == [tmp_path / name for name in expected_names]
    assert find_log_files(unrelated_log) == [unrelated_log]


def test_reader_warns_on_unreadable_file_and_keeps_other_files(
    tmp_path: Path,
) -> None:
    unreadable_log = tmp_path / "traces-1-unreadable.log"
    valid_log = tmp_path / "traces-2-valid.log"
    unreadable_log.write_bytes(b"\xff")
    _write_log(valid_log, [_record("root", "_input_event")])

    result = _run_reader(tmp_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["warnings"] == [
        {
            "code": "UNREADABLE_FILE",
            "message": f"Could not read Trace Log file {unreadable_log}.",
            "filePath": str(unreadable_log),
        }
    ]
    assert "[UNREADABLE_FILE]" in result.stderr


def test_reader_preserves_reused_event_edges_across_detail_levels(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    reused_record = _record(
        "shared-output",
        "SharedEvent",
        "root-1",
        "shared_action",
        {"value": {"truncatedString": "abc...", "omittedChars": 3}},
    )
    reused_record["detail"] = "STANDARD"
    verbose_record = _record(
        "shared-output", "SharedEvent", "root-2", "shared_action", {"value": "abcdef"}
    )
    verbose_record["detail"] = "VERBOSE"
    _write_log(
        log_path,
        [
            _record("root-1", "_input_event"),
            reused_record,
            reused_record,
            _record("root-2", "_input_event"),
            verbose_record,
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


def test_reader_prunes_multi_node_cycle_and_keeps_reachable_trace(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("event-a", "MiddleEvent", "root", "root_to_a"),
            _record("event-b", "MiddleEvent", "event-a", "a_to_b"),
            _record("event-c", "MiddleEvent", "event-b", "b_to_c"),
            _record("event-a", "MiddleEvent", "event-c", "c_to_a"),
        ],
    )

    json_result = _run_reader(log_path, "json")
    trace_forest = json.loads(json_result.stdout)

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "root_to_a", "children": ["event-a"]}
    ]
    assert trace_forest["nodes"]["event-a"]["actions"] == [
        {"name": "a_to_b", "children": ["event-b"]}
    ]
    assert trace_forest["nodes"]["event-b"]["actions"] == [
        {"name": "b_to_c", "children": ["event-c"]}
    ]
    assert trace_forest["nodes"]["event-c"]["actions"] == []
    assert trace_forest["nodes"]["event-a"]["upstreamEdges"] == [
        {
            "upstreamEventId": "root",
            "upstreamActionName": "root_to_a",
        }
    ]
    assert trace_forest["warnings"] == [
        {
            "code": "CYCLE_DETECTED",
            "eventId": "event-a",
            "message": (
                "Lineage edge from event-c to event-a through Action c_to_a "
                "creates a cycle."
            ),
            "upstreamEventId": "event-c",
            "upstreamActionName": "c_to_a",
        }
    ]

    text_result = _run_reader(log_path, "text")

    assert text_result.stdout.count("MiddleEvent (event-a)") == 1
    assert text_result.stdout.count("MiddleEvent (event-b)") == 1
    assert text_result.stdout.count("MiddleEvent (event-c)") == 1
    assert "[CYCLE_DETECTED]" in text_result.stderr


def test_reader_prunes_same_cycle_edge_independent_of_log_order(
    tmp_path: Path,
) -> None:
    root_1 = _record("root-1", "_input_event")
    root_2 = _record("root-2", "_input_event")
    root_1_to_a = _record("event-a", "MiddleEvent", "root-1", "root_1_to_a")
    b_to_a = _record("event-a", "MiddleEvent", "event-b", "b_to_a")
    a_to_b = _record("event-b", "MiddleEvent", "event-a", "a_to_b")
    root_2_to_b = _record("event-b", "MiddleEvent", "root-2", "root_2_to_b")
    record_orders = [
        [root_1, root_1_to_a, a_to_b, root_2, root_2_to_b, b_to_a],
        [root_2, root_2_to_b, b_to_a, root_1, root_1_to_a, a_to_b],
    ]

    results = []
    for index, records in enumerate(record_orders):
        log_path = tmp_path / f"traces-{index}.log"
        _write_log(log_path, records)
        reader_result = _run_reader(log_path, "json")
        trace_forest = json.loads(reader_result.stdout)
        results.append(
            {
                "roots": trace_forest["roots"],
                "event-a-actions": trace_forest["nodes"]["event-a"]["actions"],
                "event-b-actions": trace_forest["nodes"]["event-b"]["actions"],
                "cycle-warning": [
                    (item["eventId"], item["message"])
                    for item in trace_forest["warnings"]
                    if item["code"] == "CYCLE_DETECTED"
                ],
            }
        )

    assert results == [
        {
            "roots": ["root-1", "root-2"],
            "event-a-actions": [{"name": "a_to_b", "children": ["event-b"]}],
            "event-b-actions": [],
            "cycle-warning": [
                (
                    "event-a",
                    "Lineage edge from event-b to event-a through Action b_to_a "
                    "creates a cycle.",
                )
            ],
        },
        {
            "roots": ["root-2", "root-1"],
            "event-a-actions": [{"name": "a_to_b", "children": ["event-b"]}],
            "event-b-actions": [],
            "cycle-warning": [
                (
                    "event-a",
                    "Lineage edge from event-b to event-a through Action b_to_a "
                    "creates a cycle.",
                )
            ],
        },
    ]


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


def test_reader_keeps_matching_observations_and_descendants_on_event_id_conflict(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record(
                "conflicting",
                "ConflictingEvent",
                "root",
                "root_to_conflicting",
            ),
            _record("other-root", "_input_event"),
            _record(
                "conflicting",
                "DifferentEvent",
                "other-root",
                "other_to_conflicting",
            ),
            _record("replay-root", "_input_event"),
            _record(
                "conflicting",
                "ConflictingEvent",
                "replay-root",
                "replay_to_conflicting",
            ),
            _record("child", "ChildEvent", "conflicting", "conflicting_to_child"),
            _record("grandchild", "ChildEvent", "child", "child_to_grandchild"),
            _record(
                "great-grandchild",
                "ChildEvent",
                "grandchild",
                "grandchild_to_great_grandchild",
            ),
        ],
    )

    json_result = _run_reader(log_path, "json")
    trace_forest = json.loads(json_result.stdout)

    assert trace_forest["roots"] == ["root", "other-root", "replay-root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "root_to_conflicting", "children": ["conflicting"]}
    ]
    assert trace_forest["nodes"]["conflicting"]["observationCount"] == 3
    assert trace_forest["nodes"]["conflicting"]["upstreamEdges"] == [
        {
            "upstreamEventId": "root",
            "upstreamActionName": "root_to_conflicting",
        },
        {
            "upstreamEventId": "replay-root",
            "upstreamActionName": "replay_to_conflicting",
        },
    ]
    assert trace_forest["nodes"]["other-root"]["actions"] == []
    assert trace_forest["nodes"]["replay-root"]["actions"] == [
        {"name": "replay_to_conflicting", "children": ["conflicting"]}
    ]
    assert trace_forest["nodes"]["conflicting"]["actions"] == [
        {"name": "conflicting_to_child", "children": ["child"]}
    ]
    assert trace_forest["nodes"]["child"]["actions"] == [
        {"name": "child_to_grandchild", "children": ["grandchild"]}
    ]
    assert trace_forest["nodes"]["grandchild"]["actions"] == [
        {
            "name": "grandchild_to_great_grandchild",
            "children": ["great-grandchild"],
        }
    ]
    assert trace_forest["warnings"] == [
        {
            "code": "EVENT_ID_CONFLICT",
            "eventId": "conflicting",
            "message": (
                "Event ID conflicting has inconsistent Event type across "
                "3 records. Conflicting observation records lineage from other-root "
                "to conflicting through Action other_to_conflicting; that observation "
                "does not contribute lineage to the canonical Event node."
            ),
            "upstreamEventId": "other-root",
            "upstreamActionName": "other_to_conflicting",
        }
    ]

    text_result = _run_reader(log_path, "text")

    for event_id in ("conflicting", "child", "grandchild", "great-grandchild"):
        assert text_result.stdout.count(f"({event_id})") == 2
    assert "[EVENT_ID_CONFLICT]" in text_result.stderr


def test_reader_deduplicates_conflict_warnings_by_recorded_lineage(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    root_b_conflict = _record(
        "conflicting",
        "DifferentEvent",
        "root-b",
        "action-b",
    )
    root_c_conflict = _record(
        "conflicting",
        "DifferentEvent",
        "root-c",
        "action-c",
    )
    _write_log(
        log_path,
        [
            _record("root-a", "_input_event"),
            _record(
                "conflicting",
                "ConflictingEvent",
                "root-a",
                "action-a",
            ),
            _record("root-b", "_input_event"),
            root_b_conflict,
            root_b_conflict,
            _record("root-c", "_input_event"),
            root_c_conflict,
            root_c_conflict,
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)

    assert trace_forest["nodes"]["conflicting"]["observationCount"] == 5
    assert trace_forest["nodes"]["conflicting"]["upstreamEdges"] == [
        {"upstreamEventId": "root-a", "upstreamActionName": "action-a"}
    ]
    assert trace_forest["nodes"]["root-b"]["actions"] == []
    assert trace_forest["nodes"]["root-c"]["actions"] == []
    assert [
        (warning["upstreamEventId"], warning["upstreamActionName"])
        for warning in trace_forest["warnings"]
    ] == [("root-b", "action-b"), ("root-c", "action-c")]
    assert result.stderr.count("EVENT_ID_CONFLICT") == 2


def test_reader_warns_on_missing_lineage_and_keeps_valid_nodes(
    tmp_path: Path,
) -> None:
    log_path = tmp_path / "events.log"
    _write_log(
        log_path,
        [
            _record("root", "_input_event"),
            _record("valid-child", "ChildEvent", "root", "valid_action"),
            _record("unlinked", "UnlinkedEvent"),
            _record("unnamed", "UnnamedEvent", "root"),
            _record("missing", "MissingEvent", "absent", "missing_action"),
        ],
    )

    result = _run_reader(log_path, "json")
    trace_forest = json.loads(result.stdout)
    warning_codes = {warning["code"] for warning in trace_forest["warnings"]}

    assert trace_forest["roots"] == ["root"]
    assert trace_forest["nodes"]["root"]["actions"] == [
        {"name": "valid_action", "children": ["valid-child"]},
    ]
    assert "unlinked" in trace_forest["nodes"]
    assert "unnamed" in trace_forest["nodes"]
    assert "missing" in trace_forest["nodes"]
    assert warning_codes == {
        "MISSING_ACTION_NAME",
        "MISSING_PARENT",
        "UNLINKED_EVENT",
    }
    assert "MISSING_PARENT" in result.stderr
    assert "MISSING_ACTION_NAME" in result.stderr
    assert "UNLINKED_EVENT" in result.stderr
