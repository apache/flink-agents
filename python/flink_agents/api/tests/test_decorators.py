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
from typing import List

import pytest

from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def test_action_decorator() -> None:  # noqa D103
    @action(InputEvent)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = event.input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_listen_events")
    listen_events = forward_action._listen_events
    assert listen_events == (InputEvent,)


def test_action_decorator_listen_multi_events() -> None:  # noqa D103
    @action(InputEvent, OutputEvent)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = event.input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_listen_events")
    listen_events = forward_action._listen_events
    assert listen_events == (InputEvent, OutputEvent)


def test_action_decorator_listen_no_event() -> None:  # noqa D103
    with pytest.raises(AssertionError):

        @action()
        def forward_action(event: Event, ctx: RunnerContext) -> None:
            input = event.input
            ctx.send_event(OutputEvent(output=input))


def test_action_decorator_listen_non_event_type() -> None:  # noqa D103
    with pytest.raises(AssertionError):

        @action(List)
        def forward_action(event: Event, ctx: RunnerContext) -> None:
            input = event.input
            ctx.send_event(OutputEvent(output=input))
