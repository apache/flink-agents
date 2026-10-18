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
################################################################################
"""Built-in invocation, retry and reporting for decision model requests."""

import time
from concurrent.futures import CancelledError
from functools import wraps
from typing import TYPE_CHECKING, cast

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decision_models import DecisionRequest, DecisionResult
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseEvent,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporters,
)
from flink_agents.plan.actions.action import Action
from flink_agents.plan.function import PythonFunction

if TYPE_CHECKING:
    from flink_agents.api.decision_models import BaseDecisionModelSetup
    from flink_agents.api.metric_group import MetricGroup


def _record_retry_metrics(
    metric_group: "MetricGroup | None",
    model_resource: str,
    retry_count: int,
    total_retry_wait_sec: int,
) -> None:
    if metric_group is None or retry_count == 0:
        return
    resource_group = metric_group.get_sub_group("model_resource", model_resource)
    resource_group.get_counter("retryCount").inc(retry_count)
    resource_group.get_counter("retryWaitSec").inc(total_retry_wait_sec)


def _record_token_metrics(
    metric_group: "MetricGroup | None",
    model_resource: str,
    response: DecisionResult,
) -> None:
    if metric_group is None or response.usage is None:
        return
    resource_group = metric_group.get_sub_group("model_resource", model_resource)
    usage_group = (
        resource_group.get_sub_group("model", response.model)
        if response.model
        else resource_group
    )
    for count, name in (
        (response.usage.input_tokens, "promptTokens"),
        (response.usage.output_tokens, "completionTokens"),
        (response.usage.total_tokens, "totalTokens"),
    ):
        if count is not None:
            usage_group.get_counter(name).inc(count)


async def process_decision_request(
    event: DecisionRequestEvent, ctx: RunnerContext
) -> None:
    """Return one correlated result after retrying ordinary provider failures.

    Refusals are successful results. Resource lookup failures, cancellation, durable
    execution failures and response-delivery failures propagate to the Action runtime.
    """
    decision_model = cast(
        "BaseDecisionModelSetup",
        ctx.get_resource(event.model, ResourceType.DECISION_MODEL),
    )

    @wraps(decision_model.decide)
    def invoke(request: DecisionRequest) -> tuple[DecisionResult | None, str | None]:
        try:
            response = decision_model.decide(
                request, request_params=event.request_params
            )
        except (CancelledError, InterruptedError):
            raise
        except Exception as error:
            return None, f"{type(error).__name__}: {error}"
        if not isinstance(response, DecisionResult):
            return None, "TypeError: DecisionModel must return a DecisionResult."
        return response, None

    decision_async = ctx.config.get(AgentExecutionOptions.DECISION_ASYNC)
    max_retries = max(0, ctx.config.get(AgentExecutionOptions.MAX_RETRIES))
    retry_interval = max(0, ctx.config.get(AgentExecutionOptions.RETRY_WAIT_INTERVAL))
    retry_count = 0
    total_retry_wait_sec = 0
    response = None
    failure = None

    try:
        for attempt in range(max_retries + 1):
            execution_id = ExecutionReporters.started(
                ctx, ExecutionEntityTypes.LLM, event.model, {}
            )
            # Durable-state failures propagate without being attributed to the model.
            if decision_async:
                response, failure = await ctx.durable_execute_async(
                    invoke, event.request, durable_id="decide"
                )
            else:
                response, failure = ctx.durable_execute(
                    invoke, event.request, durable_id="decide"
                )
            if failure is None:
                ExecutionReporters.succeeded(
                    ctx, execution_id, ExecutionEntityTypes.LLM, event.model, {}
                )
                _record_token_metrics(ctx.action_metric_group, event.model, response)
                break
            ExecutionReporters.failed(
                ctx,
                execution_id,
                ExecutionEntityTypes.LLM,
                event.model,
                {},
                RuntimeError(failure),
                ExecutionProblemCategories.MODEL_CALL_FAILED,
            )
            if attempt < max_retries:
                retry_count += 1
                wait_sec = retry_interval * (1 << attempt)
                if wait_sec > 0:
                    time.sleep(wait_sec)
                    total_retry_wait_sec += wait_sec
    finally:
        _record_retry_metrics(
            ctx.action_metric_group,
            event.model,
            retry_count,
            total_retry_wait_sec,
        )
    if failure is not None:
        ctx.send_event(
            DecisionResponseEvent.failed(
                event.id, failure, retry_count, total_retry_wait_sec
            )
        )
    else:
        ctx.send_event(
            DecisionResponseEvent.success(
                event.id, response, retry_count, total_retry_wait_sec
            )
        )


DECISION_MODEL_ACTION = Action(
    name="decision_model_action",
    exec=PythonFunction.from_callable(process_decision_request),
    trigger_conditions=[DecisionRequestEvent.EVENT_TYPE],
)
