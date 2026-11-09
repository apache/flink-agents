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
################################################################################
"""Importable Python async Action for the checkpoint progress E2E."""

import time
from pathlib import Path

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.runner_context import RunnerContext


def wait_for_release(directory: str) -> int:
    """Simulate slow external I/O until the test explicitly releases the call."""
    gate = Path(directory)
    (gate / "entered").touch()
    deadline = time.monotonic() + 90
    while not (gate / "release").exists():
        if time.monotonic() >= deadline:
            msg = "Checkpoint E2E did not release the async call"
            raise TimeoutError(msg)
        time.sleep(0.05)
    return 1


def mark_submitted(value: str) -> str:
    """Acknowledge source emission before the test requests a checkpoint."""
    (Path(value) / "submitted").touch()
    return value


def record_output(value: tuple[str, int]) -> str:
    """Publish downstream results atomically and reject duplicate outputs."""
    directory, count = value
    temporary = Path(directory) / "output.tmp"
    with temporary.open("x", encoding="utf-8") as output:
        output.write(str(count))
    # Keep the temporary file as a duplicate-output guard.
    (Path(directory) / "output").hardlink_to(temporary)
    return directory


class CheckpointAgent(Agent):
    """Park a Python coroutine while Flink checkpoints the pending Action."""

    @action(EventType.InputEvent)
    @staticmethod
    async def run(event: Event, ctx: RunnerContext) -> None:
        """Await through the public framework API, then verify memory access."""
        directory = InputEvent.from_event(event).input
        value = ctx.short_term_memory.get("count") or 0
        result = await ctx.durable_execute_async(wait_for_release, directory)
        ctx.short_term_memory.set("count", value + result)
        ctx.send_event(OutputEvent(output=(directory, value + result)))
