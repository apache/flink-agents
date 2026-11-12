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
"""Agent for the native-timer PyFlink E2E test."""

from datetime import datetime, timedelta, timezone

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.runner_context import RunnerContext, TimerMode


def _tick_event(key: int, step: int) -> Event:
    return Event(
        type="tick",
        attributes={"key": key, "step": step},
        attachments={"payload": bytes([key])},
    )


class NativeTimerAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def input(event: Event, ctx: RunnerContext) -> None:
        key = int(InputEvent.from_event(event).input)
        ctx.short_term_memory.set("key", key)
        ctx.short_term_memory.set("ticks", 0)
        ctx.sensory_memory.set("origin", True)
        ctx.register_timer(
            "watch",
            timedelta(milliseconds=50),
            _tick_event(key, -2),
            mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
        )
        event = _tick_event(key, 1)
        ctx.register_timer(
            "watch",
            timedelta(milliseconds=100),
            event,
            mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
        )
        event.attachments["payload"] = bytes([99])
        ctx.register_timer(
            "cancelled",
            timedelta(milliseconds=50),
            _tick_event(key, -1),
            mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
        )
        ctx.cancel_timer("cancelled")
        ctx.cancel_timer("missing")

    @action("tick")
    @staticmethod
    def tick(event: Event, ctx: RunnerContext) -> None:
        key = int(event.attributes["key"])
        step = int(event.attributes["step"])
        assert ctx.sensory_memory.get("origin") is None
        assert ctx.short_term_memory.get("key") == key
        assert event.attachments["payload"] == bytes([key])
        assert step == ctx.short_term_memory.get("ticks") + 1
        ctx.short_term_memory.set("ticks", step)
        ctx.send_event(OutputEvent(output=f"{key}:tick:{step}"))
        if step < 3:
            ctx.register_timer(
                "watch",
                timedelta(milliseconds=50),
                _tick_event(key, step + 1),
                mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
            )
        else:
            ctx.register_timer_at(
                "finish",
                datetime.now(timezone.utc) + timedelta(milliseconds=100),
                Event(type="finish", attributes={"key": key}),
                mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
            )

    @action("finish")
    @staticmethod
    def finish(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(OutputEvent(output=f"{int(event.attributes['key'])}:done"))
