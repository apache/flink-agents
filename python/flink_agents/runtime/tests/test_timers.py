# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import json
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

import pytest

from flink_agents.api.events.event import Event
from flink_agents.api.memory_object import MemoryType
from flink_agents.api.memory_reference import MemoryRef
from flink_agents.api.runner_context import TimerMode
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


@pytest.fixture
def context():
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = MagicMock()
    return ctx


def test_relative_timer_preserves_event_and_rounds_up(context):
    event = Event(type="approval_timeout", attributes={"request_id": "42"})
    context.register_timer(
        "approval:42",
        timedelta(microseconds=1001),
        event,
        mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
    )
    timer_id, millis, payload, mode = (
        context._j_runner_context.registerTimerJson.call_args.args
    )
    assert (timer_id, millis, mode) == ("approval:42", 2, "TRIGGER_AFTER_CURRENT_RUN")
    assert json.loads(payload)["id"] == str(event.id)
    assert json.loads(payload)["type"] == "approval_timeout"
    assert json.loads(payload)["attributes"] == {"request_id": "42"}


def test_absolute_timer_normalizes_timezone_without_float_rounding(context):
    deadline = datetime(2026, 1, 1, 8, 0, 0, 1, tzinfo=timezone(timedelta(hours=8)))
    context.register_timer_at(
        "watch", deadline, Event(type="watch"), mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN
    )
    args = context._j_runner_context.registerTimerAtJson.call_args.args
    assert args[1] == 1767225600001
    assert args[3] == "TRIGGER_AFTER_CURRENT_RUN"


@pytest.mark.parametrize("timer_id", ["", " ", None, 42])
def test_invalid_id_rejected_before_bridge(context, timer_id):
    with pytest.raises(ValueError, match="ID"):
        context.cancel_timer(timer_id)
    context._j_runner_context.cancelTimer.assert_not_called()


@pytest.mark.parametrize("delay", [timedelta(microseconds=-1), 1])
def test_invalid_delay_rejected(context, delay):
    with pytest.raises(ValueError, match="non-negative"):
        context.register_timer(
            "watch",
            delay,
            Event(type="watch"),
            mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
        )


def test_naive_deadline_rejected(context):
    with pytest.raises(ValueError, match="timezone-aware"):
        context.register_timer_at(
            "watch",
            datetime(2026, 1, 1),
            Event(type="watch"),
            mode=TimerMode.TRIGGER_AFTER_CURRENT_RUN,
        )


def test_cancel_uses_same_id(context):
    context.cancel_timer("approval:42")
    context._j_runner_context.cancelTimer.assert_called_once_with("approval:42")


@pytest.mark.parametrize("mode", [None, "trigger_after_current_run", "unsupported", 1])
@pytest.mark.parametrize("absolute", [False, True])
def test_invalid_mode_rejected_before_event_snapshot_or_bridge(context, mode, absolute):
    event = MagicMock(spec=Event)
    register = context.register_timer_at if absolute else context.register_timer
    time = (
        datetime(2026, 1, 1, tzinfo=timezone.utc) if absolute else timedelta(seconds=1)
    )
    with pytest.raises(TypeError, match="TimerMode"):
        register("watch", time, event, mode)
    event.model_dump_json.assert_not_called()
    context._j_runner_context.registerTimerJson.assert_not_called()
    context._j_runner_context.registerTimerAtJson.assert_not_called()


@pytest.mark.parametrize("absolute", [False, True])
@pytest.mark.parametrize(
    "attachment",
    [b"payload", MemoryRef.create(MemoryType.SENSORY, "payload"), object()],
    ids=["value", "reference", "non_serializable"],
)
def test_attachments_rejected_before_memory_access_or_bridge(
    context, absolute, attachment
):
    event = Event(type="watch", attachments={"payload": attachment})
    register = context.register_timer_at if absolute else context.register_timer
    time = (
        datetime(2026, 1, 1, tzinfo=timezone.utc) if absolute else timedelta(seconds=1)
    )
    with pytest.raises(
        ValueError, match="Timer Events that start a new run cannot carry attachments"
    ):
        register("watch", time, event, TimerMode.TRIGGER_AFTER_CURRENT_RUN)
    assert event.attachments["payload"] is attachment
    assert context._j_runner_context.mock_calls == []
