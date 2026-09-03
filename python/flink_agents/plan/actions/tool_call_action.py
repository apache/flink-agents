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
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import wraps
from typing import Any

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import DurableCall, Outcome, RunnerContext
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


@dataclass
class _ToolCallOccurrence:
    started_at: datetime | None = None
    finished_at: datetime | None = None

    def wrap(self, func: Callable[..., Any]) -> Callable[..., Any]:
        @wraps(func)
        def observed_call(*args: Any, **kwargs: Any) -> Any:
            self.started_at = datetime.now(timezone.utc)
            try:
                return func(*args, **kwargs)
            finally:
                self.finished_at = datetime.now(timezone.utc)

        return observed_call


@dataclass(frozen=True)
class _ToolCallExecution:
    id: str
    name: str
    durable_call: DurableCall
    entity_metadata: dict[str, Any]
    occurrence: _ToolCallOccurrence


async def process_tool_request(event: Event, ctx: RunnerContext) -> None:
    """Built-in action for processing tool call requests."""
    event = ToolRequestEvent.from_event(event)
    tool_call_async = ctx.config.get(AgentExecutionOptions.TOOL_CALL_ASYNC)
    tool_call_parallelism = ctx.config.get(AgentExecutionOptions.TOOL_CALL_PARALLELISM)

    if tool_call_async:
        # To avoid https://github.com/alibaba/pemja/issues/88, we log a message here.
        _logger.debug("Processing tool call asynchronously.")

    responses = {}
    success = {}
    error = {}
    external_ids = {}
    executions = _build_tool_call_executions(
        event,
        ctx,
        responses,
        success,
        error,
        external_ids,
    )

    if tool_call_async and tool_call_parallelism > 1 and len(executions) > 1:
        await _execute_parallel(executions, ctx, responses, success, error)
    else:
        await _execute_sequentially(
            executions,
            tool_call_async=tool_call_async,
            ctx=ctx,
            responses=responses,
            success=success,
            error=error,
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


def _build_tool_call_executions(
    event: ToolRequestEvent,
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
    external_ids: dict,
) -> list[_ToolCallExecution]:
    executions = []
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

        occurrence = _ToolCallOccurrence()
        executions.append(
            _ToolCallExecution(
                id=call_id,
                name=name,
                durable_call=DurableCall(
                    func=occurrence.wrap(tool.call),
                    kwargs=call_kwargs,
                ),
                entity_metadata=entity_metadata,
                occurrence=occurrence,
            )
        )
    return executions


async def _execute_parallel(
    executions: list[_ToolCallExecution],
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    outcomes: list[Outcome] = []
    result_observed_at = None
    try:
        outcomes = await ctx.durable_execute_all_async(
            [execution.durable_call for execution in executions]
        )
        result_observed_at = datetime.now(timezone.utc)
        for execution, outcome in zip(executions, outcomes, strict=True):
            _record_outcome(execution, outcome, responses, success, error)
    except Exception as e:
        if result_observed_at is None:
            result_observed_at = datetime.now(timezone.utc)
        for execution in executions:
            _record_execution_exception(execution, e, responses, success, error)
    finally:
        for index, execution in enumerate(executions):
            _report_execution(
                execution,
                ctx,
                outcomes[index] if index < len(outcomes) else None,
                result_observed_at,
            )


async def _execute_sequentially(
    executions: list[_ToolCallExecution],
    *,
    tool_call_async: bool,
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    for execution in executions:
        outcome = None
        result_observed_at = None
        try:
            call = execution.durable_call
            if tool_call_async:
                response = await ctx.durable_execute_async(
                    call.func,
                    *call.args,
                    **(call.kwargs or {}),
                )
            else:
                response = ctx.durable_execute(
                    call.func,
                    *call.args,
                    **(call.kwargs or {}),
                )
            result_observed_at = datetime.now(timezone.utc)
            outcome = Outcome.success(response)
            responses[execution.id] = response
            success[execution.id] = True
        except Exception as e:
            if result_observed_at is None:
                result_observed_at = datetime.now(timezone.utc)
            outcome = Outcome.failure(e)
            _record_execution_exception(execution, e, responses, success, error)
        finally:
            _report_execution(execution, ctx, outcome, result_observed_at)


def _record_outcome(
    execution: _ToolCallExecution,
    outcome: Outcome,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    if outcome.is_failure():
        _record_execution_exception(execution, outcome.error, responses, success, error)
    else:
        responses[execution.id] = outcome.value
        success[execution.id] = True


def _record_execution_exception(
    execution: _ToolCallExecution,
    exception: BaseException,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    responses[execution.id] = f"Tool `{execution.name}` execute failed."
    success[execution.id] = False
    error[execution.id] = str(exception)


def _report_execution(
    execution: _ToolCallExecution,
    ctx: RunnerContext,
    outcome: Outcome | None,
    result_observed_at: datetime | None,
) -> None:
    finished_at = execution.occurrence.finished_at
    started_at = execution.occurrence.started_at
    if started_at is not None:
        ExecutionReporters.started_at(
            ctx,
            ExecutionEntityTypes.TOOL,
            execution.name,
            execution.entity_metadata,
            started_at.isoformat().replace("+00:00", "Z"),
        )
    if outcome is None:
        return
    # A timed-out callable may finish after the Action already received its failure.
    if finished_at is None or finished_at > result_observed_at:
        finished_at = result_observed_at
    finished_timestamp = finished_at.isoformat().replace("+00:00", "Z")
    if outcome.is_success():
        ExecutionReporters.succeeded_at(
            ctx,
            ExecutionEntityTypes.TOOL,
            execution.name,
            execution.entity_metadata,
            finished_timestamp,
        )
    else:
        ExecutionReporters.failed_at(
            ctx,
            ExecutionEntityTypes.TOOL,
            execution.name,
            execution.entity_metadata,
            outcome.error,
            ExecutionProblemCategories.TOOL_CALL_FAILED,
            finished_timestamp,
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
