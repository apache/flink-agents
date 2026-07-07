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
from pathlib import Path

from ollama import Client

current_dir = Path(__file__).parent


def _normalize_arguments(arguments: object) -> dict:
    """Return tool-call arguments as a dict, parsing a JSON string if needed.

    Args:
        arguments: Tool-call arguments, either a mapping (Ollama path), a
            JSON-encoded string (some providers ``json.dumps`` the arguments),
            or ``None`` for a no-argument tool call.

    Returns:
        The arguments as a dict; an empty dict when ``arguments`` is ``None``.
    """
    if arguments is None:
        return {}
    if isinstance(arguments, str):
        return json.loads(arguments)
    return dict(arguments)


def collect_tool_invocations(log_dir: str | Path) -> list[dict]:
    """Read ``events-*.log`` under ``log_dir`` and return tool invocations in order.

    Globs the per-subtask event-log files the ``FileEventLogger`` writes, parses
    each JSONL record, and extracts every ``_tool_request_event`` tool call. The
    tool-call dict is nested under ``function`` in the wire format.

    Args:
        log_dir: Directory containing the ``events-*.log`` files (the configured
            ``baseLogDir``).

    Returns:
        Ordered list of ``{"name": str, "arguments": dict | str}``. Empty when the
        model invoked no tool (a legitimate, assertable outcome).
    """
    invocations = []
    for log_file in sorted(Path(log_dir).glob("events-*.log")):
        with log_file.open() as handle:
            for line in handle:
                if not line.strip():
                    continue
                record = json.loads(line)
                if record.get("eventType") != "_tool_request_event":
                    continue
                tool_calls = record["event"]["attributes"].get("tool_calls", [])
                for tool_call in tool_calls:
                    function = tool_call["function"]
                    invocations.append(
                        {
                            "name": function["name"],
                            "arguments": function["arguments"],
                        }
                    )
    return invocations


def assert_tool_invoked(invocations: list[dict], name: str, arguments: dict) -> None:
    """Assert some invocation called tool ``name`` with arguments equal to ``arguments``.

    Argument values are compared after normalizing both sides to a dict (a
    JSON-string ``arguments`` is parsed first), so the comparison is
    order-independent and tolerant of providers that encode arguments as a string.

    Args:
        invocations: Tool invocations as returned by :func:`collect_tool_invocations`.
        name: Expected tool name.
        arguments: Expected tool arguments.

    Raises:
        AssertionError: If no invocation matches both ``name`` and ``arguments``;
            the message dumps the actual invocations.
    """
    expected_args = _normalize_arguments(arguments)
    for invocation in invocations:
        if invocation["name"] != name:
            continue
        if _normalize_arguments(invocation["arguments"]) == expected_args:
            return
    message = (
        f"No invocation of tool {name!r} with arguments {expected_args!r}; "
        f"actual invocations: {invocations!r}"
    )
    raise AssertionError(message)


def pull_model(ollama_model: str) -> Client:
    """Run ollama pull ollama_model."""
    try:
        # prepare ollama server
        subprocess.run(
            ["bash", f"{current_dir}/scripts/ollama_pull_model.sh", ollama_model],
            timeout=120,
            check=True,
        )
        client = Client()
        models = client.list()

        model_found = False
        for model in models["models"]:
            if model.model == ollama_model:
                model_found = True
                break

        if not model_found:
            client = None  # type: ignore
    except Exception:
        client = None  # type: ignore

    return client


def check_result(*, result_dir: Path, ground_truth_dir: Path) -> None:
    """Util function for checking flink job execution result."""
    actual_lines: list[str] = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual_lines.extend(f.readlines())
        if file.is_file():
            with file.open() as f:
                actual_lines.extend(f.readlines())

    with ground_truth_dir.open() as f:
        expected_lines = f.readlines()

    def _normalize(line: str) -> str:
        stripped = line.strip()
        try:
            return json.dumps(json.loads(stripped), sort_keys=True)
        except (json.JSONDecodeError, ValueError):
            return stripped

    actual = sorted(_normalize(line) for line in actual_lines if line.strip())
    expected = sorted(_normalize(line) for line in expected_lines if line.strip())
    assert actual, "No actual results found in result_dir"
    assert expected, "No expected results found in ground_truth_dir"
    assert actual == expected, (
        f"Result mismatch:\n  actual={actual}\n  expected={expected}"
    )
