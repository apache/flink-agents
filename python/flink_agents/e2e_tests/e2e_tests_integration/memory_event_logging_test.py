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
"""E2e test: memory events and agent-run-begin events land in the EventLog."""

import json
import os
import sysconfig
from pathlib import Path

from pyflink.common import Configuration
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.agents.agent import Agent
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext

# Include both the active environment and any source checkout already on PYTHONPATH so
# the embedded interpreter can resolve this test module.
_purelib = sysconfig.get_paths()["purelib"]
_extra_pythonpath = os.environ.get("PYTHONPATH")
os.environ["PYTHONPATH"] = (
    f"{_purelib}{os.pathsep}{_extra_pythonpath}" if _extra_pythonpath else _purelib
)

class MemoryWritingAgent(Agent):
    """Agent whose input action writes short-term memory."""

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Write STM and send an output event."""
        input_data = InputEvent.from_event(event).input
        ctx.short_term_memory.set("user.tier", "gold")
        ctx.send_event(OutputEvent(output={"processed": input_data["review"]}))


def _read_ordered_records(event_log_dir: Path) -> list[dict]:
    """Read JSON records from the single event log, preserving line order."""
    log_files = list(event_log_dir.glob("events-*.log"))
    assert len(log_files) == 1, (
        f"Expected one event log file in {event_log_dir}, found {len(log_files)}"
    )
    with log_files[0].open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def test_memory_event_logging(tmp_path: Path) -> None:
    """Default memory events plus an opt-in run-begin event land in the Event Log."""
    event_log_dir = tmp_path / "event_log"

    # All inputs share one key so run-begin STM snapshots accumulate deterministically.
    inputs = [{"id": 7, "review": f"input-{i}"} for i in range(3)]

    config = Configuration()
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set_str("baseLogDir", str(event_log_dir))
    # Memory operation events stay at defaults; run-begin snapshots are opt-in.
    agents_env.get_config().set(AgentExecutionOptions.AGENT_RUN_BEGIN_EVENT, True)
    # VERBOSE keeps the full event subtree.
    agents_env.get_config().set_str("event-log.level", "VERBOSE")

    input_datastream = env.from_collection(inputs)
    output_datastream = (
        agents_env.from_datastream(
            input=input_datastream, key_selector=lambda value: str(value["id"])
        )
        .apply(MemoryWritingAgent())
        .to_datastream()
    )
    list(output_datastream.execute_and_collect())

    records = _read_ordered_records(event_log_dir)

    types = [r["eventType"] for r in records]
    assert EventType.AgentRunBeginEvent in types
    assert EventType.ShortTermWriteEvent in types

    write = next(r for r in records if r["eventType"] == EventType.ShortTermWriteEvent)
    assert write["event"]["attributes"]["value"]["user.tier"] == "gold"
    assert "key" in write["event"]["attributes"]

    begins = [r for r in records if r["eventType"] == EventType.AgentRunBeginEvent]
    assert len(begins) == len(inputs)
    # First run-begin: empty STM; a later one contains the previous run's write.
    assert begins[0]["event"]["attributes"]["value"] == {}
    assert begins[-1]["event"]["attributes"]["value"].get("user.tier") == "gold"

    # Adjacency: each run-begin line directly follows its InputEvent line (both are
    # emitted synchronously back-to-back) — the reconstruction anchor.
    for i, record in enumerate(records):
        if record["eventType"] == EventType.AgentRunBeginEvent:
            assert i > 0, "run-begin must not be the first log line"
            assert records[i - 1]["eventType"] == EventType.InputEvent, (
                f"run-begin at line {i} should directly follow an _input_event line"
            )
