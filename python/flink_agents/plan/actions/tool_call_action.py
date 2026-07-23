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
from typing import Any

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools import ToolExecutionMetadataProvider
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    ToolParameterSource,
)
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporters,
    ToolExecutionMetadataKeys,
)
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

_logger = logging.getLogger(__name__)


def _tool_entity_metadata(
    tool_request_event_id: object,
    tool_call_id: object,
    external_id: object,
    tool_name: str,
    tool: object | None,
    kwargs: dict[str, Any],
) -> dict[str, Any]:
    metadata: dict[str, Any] = {
        ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID: str(tool_request_event_id),
        ToolExecutionMetadataKeys.TOOL_CALL_ID: str(tool_call_id),
    }
    if external_id is not None:
        metadata[ToolExecutionMetadataKeys.EXTERNAL_ID] = str(external_id)
    tool_type = tool.tool_type() if tool is not None else None
    if tool_type is not None:
        metadata[ToolExecutionMetadataKeys.TOOL_TYPE] = getattr(
            tool_type, "value", str(tool_type)
        )
    if isinstance(tool, ToolExecutionMetadataProvider):
        try:
            supplemental = tool.get_tool_execution_metadata(dict(kwargs)) or {}
        except Exception:
            _logger.debug(
                "Failed to collect execution metadata for tool %s.",
                tool_name,
                exc_info=True,
            )
            supplemental = {}
        for key, value in supplemental.items():
            if key is not None and value is not None:
                metadata.setdefault(key, value)
    return metadata


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
        external_ids[call_id] = external_id
        call_kwargs = dict(kwargs or {})

        tool = None
        preparation_error = None
        try:
            tool = ctx.get_resource(name, ResourceType.TOOL)
        except Exception as e:
            preparation_error = e
        if tool is not None:
            try:
                # Framework-owned injected args must win over model-provided values so
                # hidden context such as tenant ids cannot be spoofed by tool calls.
                call_kwargs.update(_resolve_injected_arguments(tool, ctx))
            except Exception as e:
                preparation_error = e

        entity_metadata = _tool_entity_metadata(
            event.id, call_id, external_id, name, tool, call_kwargs
        )
        ExecutionReporters.started(
            ctx, ExecutionEntityTypes.TOOL, name, entity_metadata
        )

        if not tool or preparation_error is not None:
            failure = preparation_error or RuntimeError(
                f"Tool `{name}` does not exist."
            )
            responses[call_id] = (
                f"Tool `{name}` does not exist."
                if not tool
                else f"Tool `{name}` execute failed."
            )
            success[call_id] = False
            error[call_id] = str(failure)
            ExecutionReporters.failed(
                ctx,
                ExecutionEntityTypes.TOOL,
                name,
                entity_metadata,
                failure,
                ExecutionProblemCategories.TOOL_CALL_FAILED,
            )
            continue

        try:
            if tool_call_async:
                response = await ctx.durable_execute_async(tool.call, **call_kwargs)
            else:
                response = ctx.durable_execute(tool.call, **call_kwargs)
            responses[call_id] = response
            success[call_id] = True
            ExecutionReporters.succeeded(
                ctx, ExecutionEntityTypes.TOOL, name, entity_metadata
            )
        except Exception as e:
            responses[call_id] = f"Tool `{name}` execute failed."
            success[call_id] = False
            error[call_id] = str(e)
            ExecutionReporters.failed(
                ctx,
                ExecutionEntityTypes.TOOL,
                name,
                entity_metadata,
                e,
                ExecutionProblemCategories.TOOL_CALL_FAILED,
            )
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
        msg = f"Cannot inject tool parameter from {source} because memory is not initialized."
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
