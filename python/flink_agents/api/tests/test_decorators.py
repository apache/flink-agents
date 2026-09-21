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

from flink_agents.api.decorators import ActionDeclaration, action, tool
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.function import JavaFunction, PythonFunction
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools import InjectedArg


def test_action_decorator() -> None:
    @action(EventType.InputEvent)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_trigger_conditions")
    trigger_conditions = forward_action._trigger_conditions
    assert trigger_conditions == (InputEvent.EVENT_TYPE,)


def test_action_decorator_preserves_mixed_conditions() -> None:
    @action(
        EventType.InputEvent,
        EventType.OutputEvent,
        "attributes.ready == true",
    )
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert hasattr(forward_action, "_trigger_conditions")
    trigger_conditions = forward_action._trigger_conditions
    assert trigger_conditions == (
        InputEvent.EVENT_TYPE,
        OutputEvent.EVENT_TYPE,
        "attributes.ready == true",
    )


@pytest.mark.parametrize(
    "conditions",
    [
        pytest.param((), id="empty"),
        pytest.param((" ",), id="blank"),
    ],
)
def test_action_decorator_defers_structural_validation(
    conditions: tuple[str, ...],
) -> None:
    @action(*conditions)
    def forward_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=input))

    assert forward_action._trigger_conditions == conditions


@pytest.mark.parametrize(
    "condition",
    [
        pytest.param(InputEvent, id="event-class"),
        pytest.param(42, id="integer"),
    ],
)
def test_action_decorator_rejects_non_string_conditions(condition: object) -> None:
    with pytest.raises(TypeError, match="must be a string"):

        @action(condition)  # type: ignore[arg-type]
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


def _java_target() -> JavaFunction:
    return JavaFunction.for_action("com.example.Handlers", "handle")


def test_action_applied_to_descriptor_returns_immutable_declaration() -> None:
    target = _java_target()

    declaration = action(EventType.InputEvent)(target)

    assert isinstance(declaration, ActionDeclaration)
    assert declaration.trigger_conditions == (InputEvent.EVENT_TYPE,)
    assert declaration.func is target
    assert declaration.name is None


def test_action_declaration_does_not_mutate_descriptor() -> None:
    target = _java_target()

    action(EventType.InputEvent)(target)

    # The descriptor is pure data and must stay free of declaration metadata,
    # so the same descriptor can back multiple independent declarations.
    assert not hasattr(target, "_trigger_conditions")
    assert not hasattr(target, "_action_name")


def test_action_declaration_keeps_shared_descriptor_declarations_distinct() -> None:
    shared = _java_target()

    first = action(EventType.InputEvent)(shared)
    second = action(EventType.OutputEvent, name="renamed")(shared)

    assert first.trigger_conditions == (InputEvent.EVENT_TYPE,)
    assert second.trigger_conditions == (OutputEvent.EVENT_TYPE,)
    assert second.name == "renamed"
    assert first.func is second.func is shared


def test_action_rejects_non_callable_non_descriptor() -> None:
    with pytest.raises(TypeError, match="callable or an api-layer Function"):
        action(EventType.InputEvent)("not a function")


def test_native_action_does_not_produce_declaration() -> None:
    @action(EventType.InputEvent)
    def regular(event: Event, ctx: RunnerContext) -> None:
        pass

    assert not isinstance(regular, ActionDeclaration)
    assert regular._trigger_conditions == (InputEvent.EVENT_TYPE,)
    assert not hasattr(regular, "_action_name")


def test_native_action_records_name_override() -> None:
    @action(EventType.InputEvent, name="renamed")
    def regular(event: Event, ctx: RunnerContext) -> None:
        pass

    assert regular._action_name == "renamed"


def test_empty_name_override_is_normalized_to_none() -> None:
    # An empty-string override means "no override" (mirrors Java resolveActionName):
    # it must not be recorded on a native action nor stored on a declaration.
    @action(EventType.InputEvent, name="")
    def regular(event: Event, ctx: RunnerContext) -> None:
        pass

    assert not hasattr(regular, "_action_name")

    declaration = action(EventType.InputEvent, name="")(_java_target())
    assert declaration.name is None


def test_action_rejects_java_descriptor_with_empty_qualname() -> None:
    bad = JavaFunction(qualname="", method_name="handle", parameter_types=[])
    with pytest.raises(ValueError, match="qualname"):
        action(EventType.InputEvent)(bad)


def test_action_rejects_java_descriptor_with_empty_method_name() -> None:
    bad = JavaFunction(qualname="com.example.X", method_name="", parameter_types=[])
    with pytest.raises(ValueError, match="method_name"):
        action(EventType.InputEvent)(bad)


def test_action_rejects_python_descriptor_with_empty_module() -> None:
    bad = PythonFunction(module="", qualname="handle")
    with pytest.raises(ValueError, match="module"):
        action(EventType.InputEvent)(bad)


def test_action_rejects_python_descriptor_with_empty_qualname() -> None:
    bad = PythonFunction(module="pkg.mod", qualname="")
    with pytest.raises(ValueError, match="qualname"):
        action(EventType.InputEvent)(bad)


def test_action_descriptor_error_names_override() -> None:
    bad = PythonFunction(module="pkg.mod", qualname="")
    with pytest.raises(ValueError, match="my_named_action"):
        action(EventType.InputEvent, name="my_named_action")(bad)


def test_tool_decorator_supports_injected_args() -> None:
    @tool(injected_args={"tenant_id": InjectedArg.from_config("tenant_id")})
    def query_order(order_id: str, tenant_id: str) -> str:
        return f"{tenant_id}:{order_id}"

    assert query_order._is_tool is True
    assert query_order._injected_args == {"tenant_id": InjectedArg.from_config("tenant_id")}


def test_tool_decorator_defaults_injected_arg_source_to_sensory_memory() -> None:
    @tool(injected_args={"tenant_id": {"key": "request.tenant_id"}})
    def query_order(order_id: str, tenant_id: str) -> str:
        return f"{tenant_id}:{order_id}"

    assert query_order._injected_args == {
        "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
    }


def test_tool_decorator_rejects_unknown_injected_arg_name() -> None:
    with pytest.raises(
        ValueError,
        match="Injected tool parameter\\(s\\) tenent_id do not match function",
    ):

        @tool(injected_args={"tenent_id": InjectedArg.from_config("tenant_id")})
        def query_order(order_id: str, tenant_id: str) -> str:
            return f"{tenant_id}:{order_id}"


def test_tool_decorator_allows_injected_arg_with_kwargs() -> None:
    @tool(injected_args={"tenant_id": InjectedArg.from_config("tenant_id")})
    def query_order(order_id: str, **kwargs: str) -> str:
        return f"{kwargs['tenant_id']}:{order_id}"

    assert query_order._injected_args == {"tenant_id": InjectedArg.from_config("tenant_id")}


def test_tool_decorator_rejects_list_injected_args() -> None:
    with pytest.raises(TypeError, match="'injected_args' must be a dict"):

        @tool(injected_args=["tenant_id"])
        def query_order(order_id: str, tenant_id: str) -> str:
            return f"{tenant_id}:{order_id}"


def test_tool_decorator_rejects_string_injected_arg_spec() -> None:
    with pytest.raises(TypeError, match="Unsupported injected arg spec"):

        @tool(injected_args={"tenant_id": "tenant.id"})
        def query_order(order_id: str, tenant_id: str) -> str:
            return f"{tenant_id}:{order_id}"


def test_tool_decorator_rejects_invalid_injected_arg_spec() -> None:
    with pytest.raises(TypeError, match="Unsupported injected arg spec"):

        @tool(injected_args={"tenant_id": 1})
        def query_order(order_id: str, tenant_id: str) -> str:
            return f"{tenant_id}:{order_id}"
