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
"""Submittable driver for the checkpoint recovery agent.

Run with ``flink run -py`` against a standalone cluster. Checkpointing, state
storage and the restart strategy come from the cluster configuration the harness
writes, so this program only supplies the job-scoped wiring, the file the source
watches, and the two directories the agent exchanges files through.

The source must be unbounded. A bounded source raises end-of-input right after the
record, and the agent operator's end-of-input drain then holds the mailbox thread
while the run is parked in its tool, so the checkpoint barrier is never consumed and
no checkpoint completes. A continuously-monitored file source instead stays open for
the life of the job, yet yields records from its file only once: the enumerator
remembers the paths it has handed out and carries that set in the checkpoint, so
neither a line appended later nor a restore produces a second record.
"""

import argparse
import sys
from pathlib import Path

from pyflink.common import Duration, WatermarkStrategy
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.checkpoint_recovery_agent import (
    HANDSHAKE_DIR_CONFIG_KEY,
    USER_CONTENT,
    VERDICT_DIR_CONFIG_KEY,
    CheckpointRecoveryAgent,
    CheckpointRecoveryInput,
    CheckpointRecoveryKeySelector,
)


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Drive the checkpoint recovery agent on a standalone cluster."
    )
    parser.add_argument(
        "--input-file",
        required=True,
        help="Single-line file the harness created. It triggers the run; the "
        "line's content is unused.",
    )
    parser.add_argument(
        "--handshake-dir",
        required=True,
        help="Directory the harness created for the tool handshake markers.",
    )
    parser.add_argument(
        "--verdict-dir",
        required=True,
        help="Directory the agent publishes its verdict file into.",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> None:
    """Build and submit the recovery job."""
    args = _parse_args(argv)

    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # Point at the file, not its directory: Flink's enumerator recurses into a
    # directory, so a file dropped there later could become a second record.
    input_stream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            Path(args.input_file).resolve().as_uri(),
        )
        # This call is what makes the source unbounded; a file source is otherwise
        # a bounded static set that finishes. The interval paces only the re-scans,
        # and every re-scan here finds a path already handed out, so the value is
        # not load-bearing.
        .monitor_continuously(Duration.of_millis(1000))
        .build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="checkpoint_recovery_trigger",
    ).map(
        # The line is a trigger, not data. Building the record from USER_CONTENT
        # keeps that constant in the agent module, beside the assertion that
        # matches it against the restored transcript.
        lambda _: CheckpointRecoveryInput(id=1, content=USER_CONTENT)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_config = agents_env.get_config()
    agents_config.set_str(HANDSHAKE_DIR_CONFIG_KEY, args.handshake_dir)
    agents_config.set_str(VERDICT_DIR_CONFIG_KEY, args.verdict_dir)
    # Already the default, pinned because the whole design depends on it: the tool
    # blocks for minutes, and it may only do so off the mailbox thread, or barriers
    # never reach the parked run and no checkpoint can contain the payload.
    agents_config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)

    output_datastream = (
        agents_env.from_datastream(
            input=input_stream, key_selector=CheckpointRecoveryKeySelector()
        )
        .apply(CheckpointRecoveryAgent())
        .to_datastream()
    )
    # The verdict travels through its own file; this sink only leaves a
    # human-readable copy in the TaskManager output.
    output_datastream.print()

    agents_env.execute()


if __name__ == "__main__":
    main(sys.argv[1:])
