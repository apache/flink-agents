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


def find_log_files(path: Path) -> list[Path]:
    """Return Event Log files in deterministic file-name order."""
    if path.is_file():
        return [path]
    log_files = sorted(path.glob("events-*.log"))
    return log_files or sorted(path.glob("*.log"))


def read_json_objects(path: Path) -> Iterator[dict[str, Any]]:
    """Read consecutive JSON objects separated by whitespace."""
    content = path.read_text(encoding="utf-8")
    decoder = json.JSONDecoder()
    position = 0
    while position < len(content):
        while position < len(content) and content[position].isspace():
            position += 1
        if position == len(content):
            return
        record, position = decoder.raw_decode(content, position)
        yield record


def read_event_records(path: Path) -> Iterator[dict[str, Any]]:
    """Read the fields needed to reconstruct Trace Trees."""
    log_files = find_log_files(path)
    if not log_files:
        message = f"No Event Log files found at {path}"
        raise FileNotFoundError(message)

    for log_file in log_files:
        for record in read_json_objects(log_file):
            event = record["event"]
            yield {
                "eventId": str(event["id"]),
                "eventType": record["eventType"],
                "timestamp": record.get("timestamp"),
                "upstreamEventId": event.get("upstreamEventId"),
                "upstreamActionName": event.get("upstreamActionName"),
            }


def build_trace_forest(records: Iterator[dict[str, Any]]) -> dict[str, Any]:
    """Build valid InputEvent trees while retaining auditable invalid nodes."""
    records_by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        records_by_id[record["eventId"]].append(record)

    warnings: list[dict[str, str]] = []
    nodes: dict[str, dict[str, Any]] = {}
    for event_id, matching_records in records_by_id.items():
        if len(matching_records) > 1:
            warnings.append(
                warning(
                    "DUPLICATE_EVENT_ID",
                    event_id,
                    f"Event ID {event_id} appears {len(matching_records)} times.",
                )
            )
            continue
        record = matching_records[0]
        nodes[event_id] = {
            "eventId": event_id,
            "eventType": record["eventType"],
            "timestamp": record["timestamp"],
            "upstreamEventId": record["upstreamEventId"],
            "upstreamActionName": record["upstreamActionName"],
            "actions": [],
        }

    roots: list[str] = []
    action_indexes: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    for event_id, node in nodes.items():
        upstream_event_id = node["upstreamEventId"]
        upstream_action_name = node["upstreamActionName"]

        if node["eventType"] == INPUT_EVENT_TYPE:
            if upstream_event_id is None and upstream_action_name is None:
                roots.append(event_id)
            else:
                warnings.append(
                    warning(
                        "INVALID_ROOT_LINEAGE",
                        event_id,
                        f"InputEvent {event_id} must not have upstream lineage.",
                    )
                )
            continue

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

    return {"roots": roots, "nodes": nodes, "warnings": warnings}


def warning(code: str, event_id: str, message: str) -> dict[str, str]:
    """Create one machine-readable reconstruction warning."""
    return {"code": code, "eventId": event_id, "message": message}


def render_text(trace_forest: dict[str, Any]) -> str:
    """Render valid Trace Trees without assigning meaning to sibling order."""
    lines: list[str] = []

    def render_event(event_id: str, indent: str) -> None:
        stack: list[tuple[str, Any, str]] = [("event", event_id, indent)]
        while stack:
            item_type, item, item_indent = stack.pop()
            if item_type == "event":
                node = trace_forest["nodes"][item]
                lines.append(f"{item_indent}{node['eventType']} ({item})")
                for action in reversed(node["actions"]):
                    stack.append(("action", action, item_indent))
            else:
                lines.append(f"{item_indent}  [Action: {item['name']}]")
                for child_id in reversed(item["children"]):
                    stack.append(("event", child_id, item_indent + "    "))

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

    trace_forest = build_trace_forest(read_event_records(args.path))
    for item in trace_forest["warnings"]:
        print(f"[{item['code']}] {item['message']}", file=sys.stderr)

    if args.format == "json":
        print(json.dumps(trace_forest, indent=2))
    else:
        print(render_text(trace_forest))


if __name__ == "__main__":
    main()
