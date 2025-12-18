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
import shutil
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

from flink_agents.api.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext


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


def test_python_event_logging() -> None:
    """Test that PythonEvent can be logged with readable content."""
    # Check that log files were created in the default location
    default_log_dir = Path(tempfile.gettempdir()) / "flink-agents"
    shutil.rmtree(default_log_dir, ignore_errors=True)

    config = Configuration()
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # Create agent environment
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)

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

    # Also check our custom log directory
    log_files = []
    if default_log_dir.exists():
        log_files.extend(default_log_dir.glob("events-*.log"))

    # At least one log file should exist
    assert len(log_files) > 0, (
        f"Event log files should be created in {default_log_dir}"
    )

    # Check that log files contain readable PythonEvent content
    log_content = ""
    for log_file in log_files:
        with log_file.open() as f:
            log_content += f.read()

    print(log_content)

    # Verify log contains expected content - should have readable event data via
    # eventString
    assert "processed_review" in log_content, (
        "Log should contain processed event content from eventString"
    )
    assert "eventString" in log_content, "Log should contain eventString field"
    assert "eventType" in log_content, "Log should contain event type information"
