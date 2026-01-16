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
import shutil
import sysconfig
import tempfile
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
from flink_agents.api.decorators import action
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


class InputKeySelector(KeySelector):
    """Key selector for input data."""

    def get_key(self, value: dict) -> int:
        """Extract key from input data."""
        return value.get("id", 0)


class PythonEventLoggingAgent(Agent):
    """Agent for testing PythonEvent logging."""

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event and send a PythonEvent."""
        # Send a PythonEvent that should be logged with readable content
        input_data = event.input
        ctx.send_event(
            OutputEvent(output={"processed_review": f"{input_data['review']}"})
        )


def test_python_event_logging(tmp_path: Path) -> None:
    """Test event logs are written to configured directory with expected content."""
    event_log_dir = tmp_path / "event_log"
    default_log_dir = Path(tempfile.gettempdir()) / "flink-agents"
    shutil.rmtree(default_log_dir, ignore_errors=True)

    config = Configuration()
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # Create agent environment
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set_str("baseLogDir", str(event_log_dir))

    # Set up input source
    current_dir = Path(__file__).parent
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/input/input_data.txt",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="python_event_logging_test",
    )

    # Parse input
    deserialize_datastream = input_datastream.map(lambda x: json.loads(x))

    # Apply agent
    agents_env.from_datastream(
        input=deserialize_datastream, key_selector=InputKeySelector()
    ).apply(PythonEventLoggingAgent()).to_datastream()

    # Execute the job
    agents_env.execute()

    # Check that log files were created in configured directory
    log_files = list(event_log_dir.glob("events-*.log"))

    # At least one log file should exist
    assert len(log_files) > 0, (
        f"Event log files should be created in {event_log_dir}"
    )

    # Check that log files contain structured event content
    record = None
    record_line = None
    has_processed_review = False
    for log_file in log_files:
        with log_file.open(encoding="utf-8") as handle:
            for line in handle:
                if line.strip():
                    record = json.loads(line)
                    record_line = line
                    event_payload = record.get("event", {})
                    if "processed_review" in json.dumps(event_payload):
                        has_processed_review = True
                        break
        if record is not None and has_processed_review:
            break

    assert record is not None, "Event log file is empty."
    assert record_line is not None, "Event log file is empty."
    assert "timestamp" in record
    assert "event" in record
    assert "eventType" in record["event"]
    assert has_processed_review, "Log should contain processed review content"

    event_type_idx = record_line.find('"eventType"')
    id_idx = record_line.find('"id"')
    attributes_idx = record_line.find('"attributes"')
    assert event_type_idx != -1
    assert id_idx != -1
    assert attributes_idx != -1
    assert event_type_idx < id_idx < attributes_idx
