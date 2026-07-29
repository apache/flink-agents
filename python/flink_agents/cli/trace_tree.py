#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Reconstruct InputEvent-rooted Trace Trees from an Event Log."""

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterator

INPUT_EVENT_TYPE = "_input_event"


def _json_fingerprint(value: Any) -> str:
    """Return a deterministic, type-sensitive representation of JSON data."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def find_log_files(path: Path) -> list[Path]:
    """Return Event Log files in deterministic file-name order."""
    if path.is_file():
        return [path]
    return sorted(path.glob("events-*.log"))


def read_json_objects(path: Path, warnings: list[dict[str, Any]]) -> Iterator[Any]:
    """Read consecutive JSON objects while retaining recoverable file warnings."""
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        warnings.append(
            warning(
                "UNREADABLE_FILE",
                None,
                f"Could not read Event Log file {path}.",
                file_path=path,
            )
        )
        return

    decoder = json.JSONDecoder()
    position = 0
    while position < len(content):
        while position < len(content) and content[position].isspace():
            position += 1
        if position == len(content):
            return
        try:
            record, position = decoder.raw_decode(content, position)
        except json.JSONDecodeError as error:
            warnings.append(
                warning(
                    "MALFORMED_RECORD",
                    None,
                    f"Could not decode an Event Log record in {path} "
                    f"at line {error.lineno}, column {error.colno}: {error.msg}.",
                    file_path=path,
                    line_number=error.lineno,
                    column_number=error.colno,
                )
            )
            next_record = content.find("\n{", max(error.pos, position + 1))
            if next_record < 0:
                return
            position = next_record + 1
            continue

        yield record


def read_event_records(
    path: Path, warnings: list[dict[str, Any]]
) -> Iterator[dict[str, Any]]:
    """Read the fields needed to reconstruct Trace Trees."""
    log_files = find_log_files(path)
    if not log_files:
        message = f"No Event Log files found at {path}"
        raise FileNotFoundError(message)

    for log_file in log_files:
        for record in read_json_objects(log_file, warnings):
            event_id: str | None = None
            invalid_reason: str | None = None
            if not isinstance(record, dict):
                invalid_reason = "record must be a JSON object"
            else:
                event = record.get("event")
                event_type = record.get("eventType")
                if not isinstance(event, dict):
                    invalid_reason = "field 'event' must be a JSON object"
                elif not isinstance(event.get("id"), str) or not event["id"]:
                    invalid_reason = "field 'event.id' must be a non-empty string"
                elif not isinstance(event_type, str) or not event_type:
                    invalid_reason = "field 'eventType' must be a non-empty string"
                else:
                    event_id = event["id"]
                    for field_name in ("upstreamEventId", "upstreamActionName"):
                        field_value = event.get(field_name)
                        if field_value is not None and not isinstance(field_value, str):
                            invalid_reason = (
                                f"field 'event.{field_name}' must be a string or null"
                            )
                            break

            if invalid_reason is not None:
                warnings.append(
                    warning(
                        "MALFORMED_RECORD",
                        event_id,
                        f"Invalid Event Log record in {log_file}: {invalid_reason}.",
                        file_path=log_file,
                    )
                )
                continue

            assert isinstance(record, dict)
            event = record["event"]
            assert isinstance(event, dict)
            event_content = dict(event)
            event_content.pop("upstreamEventId", None)
            event_content.pop("upstreamActionName", None)
            yield {
                "eventId": event["id"],
                "eventType": record["eventType"],
                "timestamp": record.get("timestamp"),
                "upstreamEventId": event.get("upstreamEventId"),
                "upstreamActionName": event.get("upstreamActionName"),
                "eventContent": event_content,
            }


def build_trace_forest(
    records: Iterator[dict[str, Any]],
    warnings: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Build valid InputEvent trees while retaining auditable invalid nodes."""
    records_by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        records_by_id[record["eventId"]].append(record)

    if warnings is None:
        warnings = []
    nodes: dict[str, dict[str, Any]] = {}
    lineage_edges_by_id: dict[str, list[tuple[str | None, str | None]]] = {}
    for event_id, matching_records in records_by_id.items():
        first_record = matching_records[0]
        first_content_fingerprint = _json_fingerprint(first_record["eventContent"])
        lineage_records = [first_record]
        has_conflicting_observations = False
        for record in matching_records[1:]:
            if (
                record["eventType"] != first_record["eventType"]
                or _json_fingerprint(record["eventContent"])
                != first_content_fingerprint
            ):
                has_conflicting_observations = True
            else:
                lineage_records.append(record)
        if has_conflicting_observations:
            warnings.append(
                warning(
                    "EVENT_ID_CONFLICT",
                    event_id,
                    f"Event ID {event_id} has inconsistent Event type or content "
                    f"across {len(matching_records)} records.",
                )
            )

        lineage_edges: list[tuple[str | None, str | None]] = []
        seen_edges: set[tuple[str | None, str | None]] = set()
        for record in lineage_records:
            edge = (record["upstreamEventId"], record["upstreamActionName"])
            if edge not in seen_edges:
                seen_edges.add(edge)
                lineage_edges.append(edge)
        lineage_edges_by_id[event_id] = lineage_edges

        nodes[event_id] = {
            "eventId": event_id,
            "eventType": first_record["eventType"],
            "timestamp": first_record["timestamp"],
            "observationCount": len(matching_records),
            "upstreamEdges": [
                {
                    "upstreamEventId": upstream_event_id,
                    "upstreamActionName": upstream_action_name,
                }
                for upstream_event_id, upstream_action_name in lineage_edges
                if upstream_event_id is not None or upstream_action_name is not None
            ],
            "actions": [],
        }

    roots: list[str] = []
    action_indexes: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    for event_id, node in nodes.items():
        lineage_edges = lineage_edges_by_id[event_id]

        if node["eventType"] == INPUT_EVENT_TYPE:
            if (None, None) in lineage_edges:
                roots.append(event_id)
            for upstream_event_id, upstream_action_name in lineage_edges:
                if upstream_event_id is None and upstream_action_name is None:
                    continue
                warnings.append(
                    warning(
                        "INVALID_ROOT_LINEAGE",
                        event_id,
                        f"InputEvent {event_id} must not have upstream lineage.",
                    )
                )
            continue

        for upstream_event_id, upstream_action_name in lineage_edges:
            if upstream_event_id is None:
                warnings.append(
                    warning(
                        "UNLINKED_EVENT",
                        event_id,
                        f"Non-InputEvent {event_id} has no upstream Event.",
                    )
                )
                continue
            if upstream_action_name is None:
                warnings.append(
                    warning(
                        "MISSING_ACTION_NAME",
                        event_id,
                        f"Event {event_id} has no upstream Action name.",
                    )
                )
                continue
            if upstream_event_id not in nodes:
                warnings.append(
                    warning(
                        "MISSING_PARENT",
                        event_id,
                        f"Event {event_id} references missing parent {upstream_event_id}.",
                    )
                )
                continue

            action = action_indexes[upstream_event_id].get(upstream_action_name)
            if action is None:
                action = {"name": upstream_action_name, "children": []}
                action_indexes[upstream_event_id][upstream_action_name] = action
                nodes[upstream_event_id]["actions"].append(action)
            action["children"].append(event_id)

    _prune_cycles(nodes, roots, warnings)
    return {"roots": roots, "nodes": nodes, "warnings": warnings}


def _prune_cycles(
    nodes: dict[str, dict[str, Any]],
    roots: list[str],
    warnings: list[dict[str, Any]],
) -> None:
    """Remove DFS back edges while preserving shared DAG descendants."""

    def child_edges(event_id: str) -> Iterator[tuple[str, str]]:
        return iter(
            sorted(
                (action["name"], child_id)
                for action in nodes[event_id]["actions"]
                for child_id in action["children"]
            )
        )

    visited_event_ids: set[str] = set()
    active_event_ids: set[str] = set()
    rejected_edges: set[tuple[str, str, str]] = set()
    root_ids = set(roots)
    traversal_order = sorted(root_ids) + sorted(
        event_id for event_id in nodes if event_id not in root_ids
    )

    for start_event_id in traversal_order:
        if start_event_id in visited_event_ids:
            continue

        active_event_ids.add(start_event_id)
        stack = [(start_event_id, child_edges(start_event_id))]
        while stack:
            parent_event_id, remaining_child_edges = stack[-1]
            try:
                action_name, child_event_id = next(remaining_child_edges)
            except StopIteration:
                active_event_ids.remove(parent_event_id)
                visited_event_ids.add(parent_event_id)
                stack.pop()
                continue

            if child_event_id in active_event_ids:
                rejected_edges.add((parent_event_id, action_name, child_event_id))
                warnings.append(
                    warning(
                        "CYCLE_DETECTED",
                        child_event_id,
                        f"Lineage edge from {parent_event_id} to {child_event_id} "
                        f"through Action {action_name} creates a cycle.",
                        upstream_event_id=parent_event_id,
                        upstream_action_name=action_name,
                    )
                )
                continue
            if child_event_id in visited_event_ids:
                continue

            active_event_ids.add(child_event_id)
            stack.append((child_event_id, child_edges(child_event_id)))

    for parent_event_id, action_name, child_event_id in rejected_edges:
        nodes[child_event_id]["upstreamEdges"] = [
            edge
            for edge in nodes[child_event_id]["upstreamEdges"]
            if (
                edge["upstreamEventId"],
                edge["upstreamActionName"],
            )
            != (parent_event_id, action_name)
        ]

    for parent_event_id, node in nodes.items():
        retained_actions = []
        for action in node["actions"]:
            action["children"] = [
                child_event_id
                for child_event_id in action["children"]
                if (parent_event_id, action["name"], child_event_id)
                not in rejected_edges
            ]
            if action["children"]:
                retained_actions.append(action)
        node["actions"] = retained_actions


def warning(
    code: str,
    event_id: str | None,
    message: str,
    *,
    file_path: Path | None = None,
    line_number: int | None = None,
    column_number: int | None = None,
    upstream_event_id: str | None = None,
    upstream_action_name: str | None = None,
) -> dict[str, Any]:
    """Create one machine-readable reconstruction warning."""
    item: dict[str, Any] = {"code": code}
    if event_id is not None:
        item["eventId"] = event_id
    item["message"] = message
    if file_path is not None:
        item["filePath"] = str(file_path)
    if line_number is not None:
        item["lineNumber"] = line_number
    if column_number is not None:
        item["columnNumber"] = column_number
    if upstream_event_id is not None:
        item["upstreamEventId"] = upstream_event_id
    if upstream_action_name is not None:
        item["upstreamActionName"] = upstream_action_name
    return item


def render_text(trace_forest: dict[str, Any]) -> str:
    """Render valid Trace Trees without assigning meaning to sibling order."""
    lines: list[str] = []

    def render_event(event_id: str, indent: str) -> None:
        stack: list[tuple[str, Any, str]] = [("event", event_id, indent)]
        active_event_ids: set[str] = set()
        while stack:
            item_type, item, item_indent = stack.pop()
            if item_type == "event":
                if item in active_event_ids:
                    continue
                active_event_ids.add(item)
                node = trace_forest["nodes"][item]
                lines.append(f"{item_indent}{node['eventType']} ({item})")
                stack.append(("exit", item, item_indent))
                stack.extend(
                    ("action", action, item_indent)
                    for action in reversed(node["actions"])
                )
            elif item_type == "action":
                lines.append(f"{item_indent}  [Action: {item['name']}]")
                stack.extend(
                    ("event", child_id, item_indent + "    ")
                    for child_id in reversed(item["children"])
                )
            else:
                active_event_ids.remove(item)

    for root_number, root_id in enumerate(trace_forest["roots"], start=1):
        if lines:
            lines.append("")
        lines.append(f"Trace Tree {root_number}")
        render_event(root_id, "  ")
    return "\n".join(lines)


def main() -> None:
    """Run the command-line reader."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="Event Log file or directory")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args()

    warnings: list[dict[str, Any]] = []
    trace_forest = build_trace_forest(read_event_records(args.path, warnings), warnings)
    for item in trace_forest["warnings"]:
        print(f"[{item['code']}] {item['message']}", file=sys.stderr)

    if args.format == "json":
        print(json.dumps(trace_forest, indent=2))
    else:
        print(render_text(trace_forest))


if __name__ == "__main__":
    main()
