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
import logging

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    ToolParameterSource,
)
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

_logger = logging.getLogger(__name__)


async def process_tool_request(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing tool call requests."""
    event = ToolRequestEvent.from_event(event)
    tool_call_async = ctx.config.get(AgentExecutionOptions.TOOL_CALL_ASYNC)

    if tool_call_async:
        # To avoid https://github.com/alibaba/pemja/issues/88, we log a message here.
        _logger.debug("Processing tool call asynchronously.")

    responses = {}
    success = {}
    error = {}
    external_ids = {}
    for tool_call in event.tool_calls:
        call_id = tool_call["id"]
        name = tool_call["function"]["name"]
        kwargs = tool_call["function"]["arguments"]
        external_id = tool_call.get("original_id")

        try:
            tool = ctx.get_resource(name, ResourceType.TOOL)
        except Exception as e:
            tool = None
            error[call_id] = str(e)
        if not tool:
            responses[call_id] = f"Tool `{name}` does not exist."
            success[call_id] = False
            error.setdefault(call_id, f"Tool `{name}` does not exist.")
            external_ids[call_id] = external_id
            continue
        else:
            try:
                call_kwargs = dict(kwargs or {})
                # Framework-owned injected args must win over model-provided values so
                # hidden context such as tenant ids cannot be spoofed by tool calls.
                call_kwargs.update(_resolve_injected_arguments(tool, ctx))
                if tool_call_async:
                    response = await ctx.durable_execute_async(
                        tool.call, **call_kwargs
                    )
                else:
                    response = ctx.durable_execute(tool.call, **call_kwargs)
                responses[call_id] = response
                success[call_id] = True
            except Exception as e:
                responses[call_id] = f"Tool `{name}` execute failed."
                success[call_id] = False
                error[call_id] = str(e)

        external_ids[call_id] = external_id
    ctx.send_event(
        ToolResponseEvent(
            request_id=event.id,
            responses=responses,
            external_ids=external_ids,
            success=success,
            error=error,
        )
    )


def _resolve_injected_arguments(tool: object, ctx: RunnerContext) -> dict:
    if not isinstance(tool, FunctionTool):
        return {}
    return {
        name: _resolve_injected_argument(injection, ctx)
        for name, injection in tool.injected_args.items()
    }


def _resolve_injected_argument(injection: InjectedArg, ctx: RunnerContext) -> object:
    key = injection.key
    if not key:
        msg = "Injected tool parameter is missing key"
        raise ValueError(msg)
    if injection.source == ToolParameterSource.CONFIG:
        conf_data = ctx.config.conf_data
        if key not in conf_data:
            msg = f"Missing config for injected tool parameter: {key}"
            raise ValueError(msg)
        return conf_data[key]
    if injection.source == ToolParameterSource.SENSORY_MEMORY:
        return _get_memory_value(ctx.sensory_memory, "sensory_memory", key)
    if injection.source == ToolParameterSource.SHORT_TERM_MEMORY:
        return _get_memory_value(ctx.short_term_memory, "short_term_memory", key)
    msg = f"Unsupported tool parameter source: {injection.source}"
    raise ValueError(msg)


def _get_memory_value(memory: MemoryObject, source: str, path: str) -> object:
    if memory is None:
        msg = (
            f"Cannot inject tool parameter from {source} because memory is not initialized."
        )
        raise ValueError(msg)
    if not memory.is_exist(path):
        msg = f"Missing memory path for injected tool parameter: {path}"
        raise ValueError(msg)
    value = memory.get(path)
    if isinstance(value, MemoryObject):
        msg = f"Memory path for injected tool parameter must reference a value: {path}"
        raise TypeError(msg)
    return value


TOOL_CALL_ACTION = Action(
    name="tool_call_action",
    exec=PythonFunction.from_callable(process_tool_request),
    trigger_conditions=[ToolRequestEvent.EVENT_TYPE],
)
