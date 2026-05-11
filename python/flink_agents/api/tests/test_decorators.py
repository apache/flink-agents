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
import pytest

from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def test_action_decorator() -> None:
    @action(InputEvent.EVENT_TYPE)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_listen_events")
    listen_events = forward_action._listen_events
    assert listen_events == (InputEvent.EVENT_TYPE,)


def test_action_decorator_listen_multi_events() -> None:
    @action(InputEvent.EVENT_TYPE, OutputEvent.EVENT_TYPE)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_listen_events")
    listen_events = forward_action._listen_events
    assert listen_events == (InputEvent.EVENT_TYPE, OutputEvent.EVENT_TYPE)


def test_action_decorator_listen_no_event() -> None:
    with pytest.raises(AssertionError):

        @action()
        def forward_action(event: Event, ctx: RunnerContext) -> None:
            input = InputEvent.from_event(event).input
            ctx.send_event(OutputEvent(output=input))


def test_action_decorator_listen_non_string_type() -> None:
    with pytest.raises(AssertionError):

        @action(InputEvent)  # type: ignore[arg-type]
        def forward_action(event: Event, ctx: RunnerContext) -> None:
            input = InputEvent.from_event(event).input
            ctx.send_event(OutputEvent(output=input))


def test_action_decorator_with_string_identifier() -> None:
    """Test that @action accepts a string identifier."""

    @action("MyCustomEvent")
    def my_handler(event: Event, ctx: RunnerContext) -> None:
        pass

    assert hasattr(my_handler, "_listen_events")
    assert my_handler._listen_events == ("MyCustomEvent",)


def test_action_decorator_multiple_strings() -> None:
    """Test that @action accepts multiple string identifiers."""

    @action("_input_event", "AnotherEvent")
    def mixed_handler(event: Event, ctx: RunnerContext) -> None:
        pass

    assert mixed_handler._listen_events == ("_input_event", "AnotherEvent")


def test_action_decorator_rejects_invalid_types() -> None:
    """Test that @action rejects non-string arguments."""
    with pytest.raises(AssertionError):

        @action(42)  # type: ignore[arg-type]
        def bad_handler(event: Event, ctx: RunnerContext) -> None:
            pass
