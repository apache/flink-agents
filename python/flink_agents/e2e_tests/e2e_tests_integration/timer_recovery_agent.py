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
"""Python Actions whose pending timers must survive a Flink job restart."""

import time
from datetime import datetime, timezone
from pathlib import Path

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.runner_context import RunnerContext, TimerMode

TIMER_KINDS = ("new_run", "current_run", "event_time")
PAYLOAD = b"\xff\x00timer"


class TimerRecoveryAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def register(event: Event, ctx: RunnerContext) -> None:
        kind = InputEvent.from_event(event).input
        if kind == "fail":
            return
        directory = Path(ctx.config.get_str("timer_test_dir"))
        # An external counter detects input re-execution instead of state restore.
        with (directory / f"registered-{kind}").open("a") as registrations:
            registrations.write("registered\n")
        ctx.short_term_memory.set("payload", PAYLOAD)
        ctx.sensory_memory.set("origin", kind)
        deadline = int(time.time() * 1000) + 60_000
        timeout = Event(type="timeout", attributes={"kind": kind, "value": 42})
        if kind == "event_time":
            deadline = 10
            ctx.register_event_time_timer(
                "timer", datetime.fromtimestamp(deadline / 1000, timezone.utc), timeout
            )
        else:
            mode = TimerMode.TRIGGER_AFTER_CURRENT_RUN
            if kind == "current_run":
                mode = TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER
                timeout.attachments["payload"] = PAYLOAD
            ctx.register_timer_at(
                "timer",
                datetime.fromtimestamp(deadline / 1000, timezone.utc),
                timeout,
                mode=mode,
            )
        ctx.send_event(
            Event(type="registered", attributes={"kind": kind, "deadline": deadline})
        )

    @action("registered")
    @staticmethod
    def registered(event: Event, ctx: RunnerContext) -> None:
        # This Action runs after the registering Action commits its timer and memory.
        directory = Path(ctx.config.get_str("timer_test_dir"))
        (directory / f"ready-{event.attributes['kind']}").write_text(
            str(event.attributes["deadline"])
        )

    @action("timeout")
    @staticmethod
    def timeout(event: Event, ctx: RunnerContext) -> None:
        directory = Path(ctx.config.get_str("timer_test_dir"))
        assert (directory / "recovered").exists(), "Timer fired before recovery"
        kind = event.attributes["kind"]
        assert event.attributes["value"] == 42
        assert ctx.short_term_memory.get("payload") == PAYLOAD
        if kind == "current_run":
            assert ctx.sensory_memory.get("origin") == kind
            assert event.attachments["payload"] == PAYLOAD
        else:
            assert ctx.sensory_memory.get("origin") is None
            assert not event.attachments
        ctx.send_event(OutputEvent(output=kind))
