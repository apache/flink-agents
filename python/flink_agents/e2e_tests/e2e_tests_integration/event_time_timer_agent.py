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
"""Agent exercising event-time timers through the Python entry point."""

from datetime import datetime, timedelta, timezone

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.runner_context import RunnerContext


def _deadline(millis: int) -> datetime:
    return datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=millis)


class EventTimeTimerAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def input(event: Event, ctx: RunnerContext) -> None:
        key = int(InputEvent.from_event(event).input)
        ctx.sensory_memory.set("origin", True)
        ctx.register_event_time_timer("watch", _deadline(10), Event(type="unexpected"))
        ctx.register_event_time_timer(
            "watch",
            _deadline(10),
            Event(type="tick", attributes={"key": key, "deadline": 10}),
        )
        ctx.register_event_time_timer(
            "cancelled", _deadline(10), Event(type="unexpected")
        )
        ctx.cancel_timer("cancelled")

    @action("tick")
    @staticmethod
    def tick(event: Event, ctx: RunnerContext) -> None:
        assert ctx.sensory_memory.get("origin") is None
        key, deadline = event.attributes["key"], event.attributes["deadline"]
        ctx.send_event(OutputEvent(output=f"{key}:{deadline}"))
        if deadline == 10:
            ctx.register_event_time_timer(
                "watch",
                _deadline(9),
                Event(type="tick", attributes={"key": key, "deadline": 9}),
            )

    @action("unexpected")
    @staticmethod
    def unexpected(event: Event, ctx: RunnerContext) -> None:
        msg = "Replaced or cancelled timer fired"
        raise AssertionError(msg)
