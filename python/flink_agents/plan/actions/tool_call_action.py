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
from flink_agents.api.runner_context import Outcome, RunnerContext
from flink_agents.api.subagent import (
    CALLABLE_NAME_PREFIX,
    SubagentResult,
    SubagentSetup,
)
from flink_agents.api.tools import ToolExecutionMetadataProvider, ToolResponse
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
from flink_agents.plan.actions.tool_result_utils import (
    normalize_agent_result,
    to_chat_message_content,
)
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
    func: Any
    args: tuple[Any, ...]
    kwargs: dict[str, Any]
    entity_metadata: dict[str, Any]
    occurrence: _ToolCallOccurrence
    agent: SubagentSetup | None = None
    agent_kwargs: dict[str, Any] | None = None


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
        agent = None
        preparation_error = None
        # The reserved subagent_ prefix separates the two namespaces: tool registration
        # rejects the prefix, so a prefixed callable name can only address a sub-agent.
        # Matched once here and carried down, because resolving the AGENT resource
        # raises when the name is absent and would otherwise have to be attempted for
        # every plain tool call.
        delegated = name.startswith(CALLABLE_NAME_PREFIX)
        try:
            tool, agent = _resolve_callable(name, ctx, delegated=delegated)
        except Exception as e:
            preparation_error = e
        # Injection is a tool-only contract, so a sub-agent call carries the model
        # arguments unchanged.
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
        ExecutionReporters.created(
            ctx,
            ExecutionEntityTypes.TOOL,
            name,
            entity_metadata,
        )

        unresolved = tool is None and agent is None
        if unresolved or preparation_error is not None:
            failure = preparation_error or RuntimeError(
                f"Tool `{name}` does not exist."
            )
            responses[call_id] = _preparation_failure_message(
                name, failure, delegated=delegated, unresolved=unresolved
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

        if agent is not None:
            # A sub-agent call cannot join the durable batch: submit() and awaiting the
            # handle already run through durable execution inside the setup, so wrapping
            # it again here would nest durable cursors.
            executions.append(
                _ToolCallExecution(
                    id=call_id,
                    name=name,
                    func=None,
                    args=(),
                    kwargs={},
                    entity_metadata=entity_metadata,
                    occurrence=_ToolCallOccurrence(),
                    agent=agent,
                    agent_kwargs=call_kwargs,
                )
            )
        else:
            occurrence = _ToolCallOccurrence()
            executions.append(
                _ToolCallExecution(
                    id=call_id,
                    name=name,
                    func=occurrence.wrap(tool.call),
                    args=(),
                    kwargs=call_kwargs,
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
    # Sub-agent calls run through durable execution inside the setup, so they
    # cannot join the tool batch below; they are dispatched concurrently on
    # their own (submit every call, then await each) so that, like the batched
    # tool calls, they overlap instead of running one by one. The agent phase
    # completes before the tool batch, so a sub-agent handle is never left
    # submitted-but-unawaited if the batch below raises.
    agent_executions = []
    tool_executions = []
    for execution in executions:
        if execution.agent is not None:
            agent_executions.append(execution)
        else:
            tool_executions.append(execution)
    await _dispatch_agent_executions(agent_executions, ctx, responses, success, error)
    outcomes: list[Outcome] = []
    result_observed_at = None
    try:
        futures = [
            ctx.durable_execute_async(
                execution.func, *execution.args, **execution.kwargs
            )
            for execution in tool_executions
        ]
        outcomes = await ctx.gather(*futures)
        result_observed_at = datetime.now(timezone.utc)
        for execution, outcome in zip(tool_executions, outcomes, strict=True):
            _record_outcome(execution, outcome, responses, success, error)
    except Exception as e:
        if result_observed_at is None:
            result_observed_at = datetime.now(timezone.utc)
        for execution in tool_executions:
            _record_execution_exception(execution, e, responses, success, error)
    finally:
        for index, execution in enumerate(tool_executions):
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
        if execution.agent is not None:
            await _dispatch_agent_execution(execution, ctx, responses, success, error)
            continue
        outcome = None
        result_observed_at = None
        try:
            if tool_call_async:
                response = await ctx.durable_execute_async(
                    execution.func,
                    *execution.args,
                    **execution.kwargs,
                )
            else:
                response = ctx.durable_execute(
                    execution.func,
                    *execution.args,
                    **execution.kwargs,
                )
            result_observed_at = datetime.now(timezone.utc)
            outcome = Outcome.success(response)
            _record_tool_response(execution, response, responses, success, error)
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
        _record_tool_response(execution, outcome.value, responses, success, error)


def _record_tool_response(
    execution: _ToolCallExecution,
    value: Any,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    response = value if isinstance(value, ToolResponse) else ToolResponse.success(value)
    if response.is_error():
        message = response.error_message
        responses[execution.id] = message
        success[execution.id] = False
        error[execution.id] = message
        return

    responses[execution.id] = response.result
    success[execution.id] = True


async def _dispatch_agent_execution(
    execution: _ToolCallExecution,
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    try:
        # submit() and awaiting the handle already run through durable execution inside
        # the setup, so wrapping the call again here would nest durable cursors.
        future = await execution.agent.submit(ctx, execution.agent_kwargs)
        result = await future
        _record_agent_result(execution, result, ctx, responses, success, error)
    except Exception as e:
        _record_agent_failure(execution, e, ctx, responses, success, error)


async def _dispatch_agent_executions(
    agent_executions: list[_ToolCallExecution],
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    """Run the sub-agent calls concurrently rather than one by one.

    Every ``submit`` is awaited first, so the async setups start their remote
    runs together, and each future is then awaited. Submitting and awaiting both
    follow ``agent_executions`` order, which is the deterministic tool-call
    order, so the setup's id allocator -- which assigns ids as each ``submit``
    is awaited -- hands out the same ids on every replay and the durable keys
    stay stable. The per-call try/except keeps the isolation the serial path
    had: one sub-agent failing, at submit or at await, is recorded and reported
    without stopping the rest.
    """
    # submit() runs through durable execution inside the setup, so it is not
    # wrapped here.
    submitted = []
    futures = []
    for execution in agent_executions:
        try:
            futures.append(await execution.agent.submit(ctx, execution.agent_kwargs))
            submitted.append(execution)
        except Exception as e:  # noqa: PERF203
            _record_agent_failure(execution, e, ctx, responses, success, error)
    for execution, future in zip(submitted, futures, strict=True):
        try:
            result = await future
            _record_agent_result(execution, result, ctx, responses, success, error)
        except Exception as e:  # noqa: PERF203
            _record_agent_failure(execution, e, ctx, responses, success, error)


def _record_agent_failure(
    execution: _ToolCallExecution,
    exception: BaseException,
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    _record_execution_exception(execution, exception, responses, success, error)
    ExecutionReporters.failed(
        ctx,
        ExecutionEntityTypes.TOOL,
        execution.name,
        execution.entity_metadata,
        exception,
        ExecutionProblemCategories.TOOL_CALL_FAILED,
    )


def _record_agent_result(
    execution: _ToolCallExecution,
    result: SubagentResult,
    ctx: RunnerContext,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    if result.success:
        responses[execution.id] = to_chat_message_content(
            normalize_agent_result(result.result, execution.agent.result_type())
        )
        success[execution.id] = True
        ExecutionReporters.succeeded(
            ctx,
            ExecutionEntityTypes.TOOL,
            execution.name,
            execution.entity_metadata,
        )
    else:
        # The model sees why the delegation failed, so it can correct the call
        # instead of repeating it blindly; the error map keeps the same detail
        # for observability.
        responses[execution.id] = _with_reason(
            f"Sub-agent `{execution.name}` execute failed", result.error_message
        )
        success[execution.id] = False
        error[execution.id] = result.error_message
        ExecutionReporters.failed(
            ctx,
            ExecutionEntityTypes.TOOL,
            execution.name,
            execution.entity_metadata,
            result.exception,
            ExecutionProblemCategories.TOOL_CALL_FAILED,
        )


def _record_execution_exception(
    execution: _ToolCallExecution,
    exception: BaseException,
    responses: dict,
    success: dict,
    error: dict,
) -> None:
    responses[execution.id] = (
        _with_reason(f"Sub-agent `{execution.name}` execute failed", str(exception))
        if execution.agent is not None
        else f"Tool `{execution.name}` execute failed."
    )
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
    if started_at is not None and (outcome is None or started_at <= result_observed_at):
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
    failure = (
        outcome.error if outcome.is_failure() else _tool_response_failure(outcome.value)
    )
    if failure is None:
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
            failure,
            ExecutionProblemCategories.TOOL_CALL_FAILED,
            finished_timestamp,
        )


def _tool_response_failure(value: Any) -> BaseException | None:
    if isinstance(value, ToolResponse) and value.is_error():
        return RuntimeError(value.error_message)
    return None


def _resolve_callable(
    name: str, ctx: RunnerContext, *, delegated: bool
) -> tuple[Any | None, SubagentSetup | None]:
    """Resolve a callable name the model emitted to either a tool or a sub-agent.

    ``delegated`` is the namespace the caller decided on, matched once there
    rather than again here.
    """
    if delegated:
        return None, _resolve_subagent(name[len(CALLABLE_NAME_PREFIX) :], ctx)
    return ctx.get_resource(name, ResourceType.TOOL), None


def _preparation_failure_message(
    name: str, failure: BaseException, *, delegated: bool, unresolved: bool
) -> str:
    """The message the model sees when a call could not even be prepared.

    A rejected sub-agent call carries the reason, so the model can correct the
    call instead of repeating it blindly. ``delegated`` is the namespace the
    caller decided on, matched once there rather than again here.
    """
    if delegated:
        return _with_reason(f"Sub-agent `{name}` execute failed", str(failure))
    return (
        f"Tool `{name}` does not exist."
        if unresolved
        else f"Tool `{name}` execute failed."
    )


def _with_reason(message: str, reason: str | None) -> str:
    return f"{message}: {reason}" if reason else f"{message}."


def _resolve_subagent(name: str, ctx: RunnerContext) -> SubagentSetup:
    """Resolve a sub-agent, in one lookup: the AGENT resource is fetched once and
    checked once here, and the caller carries the setup from then on.
    """
    setup = ctx.get_resource(name, ResourceType.AGENT)
    if not isinstance(setup, SubagentSetup):
        # A sub-agent owned by the other language resolves to a bridge handle here,
        # which cannot be called through this path.
        msg = (
            f"Sub-agent {name} must resolve to a SubagentSetup, "
            f"but was {type(setup).__name__}."
        )
        raise TypeError(msg)
    return setup


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
