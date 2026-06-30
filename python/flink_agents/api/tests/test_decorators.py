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
from flink_agents.api.events.event_type import EventType
from flink_agents.api.function import JavaFunction, PythonFunction
from flink_agents.api.runner_context import RunnerContext


def test_action_decorator() -> None:
    @action(EventType.InputEvent)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_trigger_conditions")
    listen_events = forward_action._trigger_conditions
    assert listen_events == (InputEvent.EVENT_TYPE,)


def test_action_decorator_listen_multi_events() -> None:
    @action(EventType.InputEvent, EventType.OutputEvent)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_trigger_conditions")
    listen_events = forward_action._trigger_conditions
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

    assert hasattr(my_handler, "_trigger_conditions")
    assert my_handler._trigger_conditions == ("MyCustomEvent",)


def test_action_decorator_multiple_strings() -> None:
    """Test that @action accepts multiple string identifiers."""

    @action("_input_event", "AnotherEvent")
    def mixed_handler(event: Event, ctx: RunnerContext) -> None:
        pass

    assert mixed_handler._trigger_conditions == ("_input_event", "AnotherEvent")


def test_action_decorator_rejects_invalid_types() -> None:
    """Test that @action rejects non-string arguments."""
    with pytest.raises(AssertionError):

        @action(42)  # type: ignore[arg-type]
        def bad_handler(event: Event, ctx: RunnerContext) -> None:
            pass


def _java_target() -> JavaFunction:
    return JavaFunction.for_action("com.example.Handlers", "handle")


def test_action_decorator_with_cross_language_target() -> None:
    target = _java_target()

    @action(EventType.InputEvent, target=target)
    def stub(event: Event, ctx: RunnerContext) -> None:
        msg = "cross-language stub"
        raise NotImplementedError(msg)

    assert stub._trigger_conditions == (InputEvent.EVENT_TYPE,)
    assert stub._target is target


def test_action_decorator_rejects_non_function_target() -> None:
    with pytest.raises(TypeError, match="api-layer Function descriptor"):

        @action(EventType.InputEvent, target="not a function")  # type: ignore[arg-type]
        def stub(event: Event, ctx: RunnerContext) -> None:
            pass


def test_action_decorator_without_target_does_not_set_attribute() -> None:
    @action(EventType.InputEvent)
    def regular(event: Event, ctx: RunnerContext) -> None:
        pass

    assert not hasattr(regular, "_target")


def test_action_decorator_rejects_java_target_with_empty_qualname() -> None:
    bad = JavaFunction(qualname="", method_name="handle", parameter_types=[])
    with pytest.raises(ValueError, match="qualname"):

        @action(EventType.InputEvent, target=bad)
        def stub(event: Event, ctx: RunnerContext) -> None:
            pass


def test_action_decorator_rejects_java_target_with_empty_method_name() -> None:
    bad = JavaFunction(qualname="com.example.X", method_name="", parameter_types=[])
    with pytest.raises(ValueError, match="method_name"):

        @action(EventType.InputEvent, target=bad)
        def stub(event: Event, ctx: RunnerContext) -> None:
            pass


def test_action_decorator_rejects_python_target_with_empty_module() -> None:
    bad = PythonFunction(module="", qualname="handle")
    with pytest.raises(ValueError, match="module"):

        @action(EventType.InputEvent, target=bad)
        def stub(event: Event, ctx: RunnerContext) -> None:
            pass


def test_action_decorator_rejects_python_target_with_empty_qualname() -> None:
    bad = PythonFunction(module="pkg.mod", qualname="")
    with pytest.raises(ValueError, match="qualname"):

        @action(EventType.InputEvent, target=bad)
        def stub(event: Event, ctx: RunnerContext) -> None:
            pass


def test_action_decorator_target_error_names_decorated_function() -> None:
    bad = PythonFunction(module="pkg.mod", qualname="")
    with pytest.raises(ValueError, match="my_named_stub"):

        @action(EventType.InputEvent, target=bad)
        def my_named_stub(event: Event, ctx: RunnerContext) -> None:
            pass
