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
import asyncio
from typing import Any

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools import InjectedArg
from flink_agents.plan.actions.tool_call_action import process_tool_request
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


def query_order(order_id: str, tenant_id: str) -> str:
    return f"{tenant_id}:{order_id}"


class _Context:
    def __init__(
        self,
        config: Any | None = None,
        injected_args: dict[str, InjectedArg] | None = None,
        sensory_memory: Any | None = None,
        short_term_memory: Any | None = None,
    ) -> None:
        self.config = config or AgentConfiguration({"tenant_id": "tenant-1"})
        if isinstance(self.config, AgentConfiguration):
            self.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
        self.injected_args = injected_args or {
            "tenant_id": InjectedArg.from_config("tenant_id")
        }
        self.sensory_memory = sensory_memory
        self.short_term_memory = short_term_memory
        self.sent_events = []

    def get_resource(self, name: str, type: ResourceType) -> FunctionTool:
        assert name == "query_order"
        assert type == ResourceType.TOOL
        return FunctionTool(
            func=PythonFunction.from_callable(query_order),
            injected_args=self.injected_args,
        )

    def durable_execute(self, func: Any, **kwargs: Any) -> Any:
        return func(**kwargs)

    async def durable_execute_async(self, func: Any, **kwargs: Any) -> Any:
        return func(**kwargs)

    def send_event(self, event: Any) -> None:
        self.sent_events.append(event)


class _Memory:
    def __init__(self, values: dict[str, Any]) -> None:
        self.values = values

    def is_exist(self, path: str) -> bool:
        return path in self.values

    def get(self, path: str) -> Any:
        return self.values[path]


class _NestedMemoryObject(MemoryObject):
    def get(self, path_or_ref: Any) -> Any:
        return None

    def set(self, path: str, value: Any) -> Any:
        raise NotImplementedError

    def new_object(self, path: str, *, overwrite: bool = False) -> "_NestedMemoryObject":
        raise NotImplementedError

    def is_exist(self, path: str) -> bool:
        return False

    def get_field_names(self) -> list[str]:
        return []

    def get_fields(self) -> dict[str, Any]:
        return {}


class _WrongConfig:
    def get(self, option: Any) -> bool:
        assert option == AgentExecutionOptions.TOOL_CALL_ASYNC
        return False


def test_tool_call_action_injects_args_from_config_without_mutating_request() -> None:
    ctx = _Context()
    arguments = {"order_id": "order-1"}
    event = ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "query_order", "arguments": arguments},
            }
        ],
    )

    asyncio.run(process_tool_request(event, ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-1:order-1"
    assert response.success["call-1"] is True
    assert response.error == {}
    assert arguments == {"order_id": "order-1"}


def test_tool_call_action_injected_arg_overrides_model_argument() -> None:
    ctx = _Context()
    arguments = {"order_id": "order-1", "tenant_id": "model-tenant"}
    event = ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "query_order", "arguments": arguments},
            }
        ],
    )

    asyncio.run(process_tool_request(event, ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-1:order-1"
    assert response.success["call-1"] is True


def test_tool_call_action_injects_args_from_sensory_memory() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
        sensory_memory=_Memory({"request.tenant_id": "tenant-sensory"}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-sensory:order-1"
    assert response.success["call-1"] is True


def test_tool_call_action_injects_args_from_short_term_memory() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_short_term_memory("session.tenant_id")
        },
        short_term_memory=_Memory({"session.tenant_id": "tenant-short"}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-short:order-1"
    assert response.success["call-1"] is True


def test_tool_call_action_reports_missing_config_injected_arg() -> None:
    ctx = _Context(AgentConfiguration({}))
    event = ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "query_order", "arguments": {"order_id": "order-1"}},
            }
        ],
    )

    asyncio.run(process_tool_request(event, ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Missing config for injected tool parameter: tenant_id"
    )


def test_tool_call_action_reports_missing_memory_path() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
        sensory_memory=_Memory({}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Missing memory path for injected tool parameter: request.tenant_id"
    )


def test_tool_call_action_reports_nested_memory_path() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
        sensory_memory=_Memory({"request.tenant_id": _NestedMemoryObject()}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Memory path for injected tool parameter must reference a value: request.tenant_id"
    )


def test_tool_call_action_reports_uninitialized_memory() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Cannot inject tool parameter from sensory_memory because memory is not initialized."
    )


def test_tool_call_action_exposes_wrong_config_type() -> None:
    ctx = _Context(config=_WrongConfig())

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert response.error["call-1"] == "'_WrongConfig' object has no attribute 'conf_data'"


def test_tool_call_action_uses_sync_execution_in_test_context() -> None:
    ctx = _Context()

    assert ctx.config.get(AgentExecutionOptions.TOOL_CALL_ASYNC) is False


def tool_request() -> ToolRequestEvent:
    return ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "query_order", "arguments": {"order_id": "order-1"}},
            }
        ],
    )
