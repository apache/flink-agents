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
import os
import subprocess
import sys
import sysconfig
from pathlib import Path

from pyflink.common import Configuration, WatermarkStrategy
from pyflink.datastream import (
    KeySelector,
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
)

from flink_agents.api.agents.agent import Agent
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


class InputKeySelector(KeySelector):
    """Key selector for input data."""

    def get_key(self, value: dict) -> int:
        """Extract key from input data."""
        return value.get("id", 0)


class PythonTraceLoggingAgent(Agent):
    """Agent for testing Python trace logging."""

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event and send an output event."""
        input_data = InputEvent.from_event(event).input
        ctx.send_event(
            OutputEvent(output={"processed_review": f"{input_data['review']}"})
        )


def test_python_trace_logging(tmp_path: Path) -> None:
    """Verify the Python runtime writes Event payloads in native TraceRecords."""
    records = _read_log_records(_run_trace_logging_pipeline(tmp_path))
    assert records, "Trace log file is empty."
    assert all(record["entityType"] == "event" for record in records)
    outputs = [
        record for record in records if record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    assert outputs, "Trace log should contain Python output Events."
    for record in outputs:
        assert record["timestamp"]
        assert record["detail"] == "STANDARD"
        assert record["entityMetadata"]["eventId"]
        assert "processed_review" in record["attributes"]["output"]


def _run_trace_logging_pipeline(
    tmp_path: Path, targets: list[dict] | None = None
) -> Path:
    """Run the trace logging pipeline and return the trace log directory.

    Args:
        tmp_path: Temporary directory for log output.
        targets: Optional trace log recording targets.

    Returns:
        The trace log directory path.
    """
    trace_log_dir = tmp_path / "trace_log"

    config = Configuration()
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set_str("trace-log.base-dir", str(trace_log_dir))

    if targets is not None:
        agents_env.get_config().set(AgentConfigOptions.TRACE_LOG_TARGETS, targets)

    current_dir = Path(__file__).parent
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/input/input_data.txt",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="python_trace_logging_test",
    )

    deserialize_datastream = input_datastream.map(lambda x: json.loads(x))

    agents_env.from_datastream(
        input=deserialize_datastream, key_selector=InputKeySelector()
    ).apply(PythonTraceLoggingAgent()).to_datastream()

    agents_env.execute()
    return trace_log_dir


def _read_log_records(trace_log_dir: Path) -> list[dict]:
    """Read all JSON records from trace log files.

    Args:
        trace_log_dir: Directory containing trace log files.

    Returns:
        List of parsed JSON records.
    """
    records: list[dict] = []
    for log_file in trace_log_dir.glob("traces-*.log"):
        with log_file.open(encoding="utf-8") as handle:
            records.extend(json.loads(line) for line in handle if line.strip())
    return records


def test_trace_tree_reads_runtime_event_lineage(tmp_path: Path) -> None:
    """The reader reconstructs the Event flow written by a PyFlink job."""
    trace_log_dir = _run_trace_logging_pipeline(tmp_path, targets=[{"scope": "ALL"}])
    records = _read_log_records(trace_log_dir)
    input_events = {
        record["entityMetadata"]["eventId"]: record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == InputEvent.EVENT_TYPE
    }
    output_events = [
        record
        for record in records
        if record["entityType"] == "event"
        and record["entityName"] == OutputEvent.EVENT_TYPE
    ]
    actions = {
        record["executionId"]: record
        for record in records
        if record["entityType"] == "action"
        and record["entityName"] == "process_input"
        and record["status"] == "started"
    }

    assert input_events
    assert len(output_events) == len(input_events)
    assert len(actions) == len(input_events)

    reader_result = subprocess.run(
        [
            sys.executable,
            "-m",
            "flink_agents.cli.trace_tree",
            str(trace_log_dir),
            "--format",
            "json",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    trace_forest = json.loads(reader_result.stdout)
    assert set(trace_forest["roots"]) == set(input_events)
    assert trace_forest["warnings"] == []
    assert reader_result.stderr == ""
    for input_event in input_events.values():
        metadata = input_event["entityMetadata"]
        assert "producerExecutionId" not in metadata
        assert "upstreamEventId" not in metadata
        assert "upstreamActionName" not in metadata

    for output_event in output_events:
        metadata = output_event["entityMetadata"]
        input_event = input_events[metadata["upstreamEventId"]]
        action_record = actions[metadata["producerExecutionId"]]
        assert metadata["upstreamActionName"] == "process_input"
        assert action_record["entityName"] == "process_input"
        assert (
            action_record["entityMetadata"]["triggerEventId"]
            == metadata["upstreamEventId"]
        )
        assert output_event["inputRunId"] == input_event["inputRunId"]
        assert action_record["inputRunId"] == input_event["inputRunId"]
        assert "parentExecutionId" not in action_record

        upstream_node = trace_forest["nodes"][metadata["upstreamEventId"]]
        process_action = next(
            action
            for action in upstream_node["actions"]
            if action["name"] == "process_input"
        )
        assert metadata["eventId"] in process_action["children"]
