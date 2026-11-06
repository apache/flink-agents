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
"""Durable route decisions and strategy execution."""

import copy
import re
import time
from concurrent.futures import CancelledError
from dataclasses import replace
from typing import Any
from uuid import UUID

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.routing import (
    RULE_PATTERN_FLAGS,
    ModelRouter,
    RoutingCandidate,
    RoutingContext,
    RoutingDecision,
    RoutingStrategyType,
)
from flink_agents.api.events.model_routing_event import ModelRoutingEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.routing.resolved_route import ResolvedModelRoute
from flink_agents.plan.routing.validation import custom_executor_class


class RoutingFailure(Exception):
    """A strategy failed; this is a request failure, never an abstention."""


def _require_decision(decision: RoutingDecision) -> None:
    if not isinstance(decision, RoutingDecision):
        msg = "Routing executor must return RoutingDecision."
        raise TypeError(msg)


def _route_once(
    router: ModelRouter, context: RoutingContext, executor: Any
) -> tuple[RoutingDecision | None, str | None]:
    try:
        start = time.monotonic_ns()
        strategy = router.strategy
        if strategy.type == RoutingStrategyType.RULE_BASED:
            decision = RoutingDecision.abstain()
            text = context.last_user_message()
            if text:
                for name, pattern in (strategy.arguments.get("rules") or {}).items():
                    if re.search(pattern, text, RULE_PATTERN_FLAGS):
                        decision = RoutingDecision(
                            name, reason=f"matched rule: {pattern}"
                        )
                        break
        else:
            decision = executor.route(strategy, context)
        _require_decision(decision)
        return replace(
            decision, decision_ms=(time.monotonic_ns() - start) / 1_000_000
        ), None
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        return None, f"{type(error).__name__}: {error}"


async def resolve(
    request_id: UUID,
    model: str,
    messages: list[ChatMessage],
    prompt_args: dict | None,
    ctx: RunnerContext,
) -> ResolvedModelRoute:
    """Select once and persist the strategy outcome before invoking candidates."""
    try:
        if not ctx.has_resource(model, ResourceType.MODEL_ROUTER):
            return ResolvedModelRoute.direct(model)
        router = ctx.get_resource(model, ResourceType.MODEL_ROUTER)
        strategy = router.strategy
        executor = None
        if strategy.type == RoutingStrategyType.CUSTOM:
            # The resource owns the prepared custom executor.
            executor = router._routing_executor
            if executor is None:
                cls, with_args = custom_executor_class(strategy.executor_class)
                executor = (
                    cls(copy.deepcopy(dict(strategy.arguments))) if with_args else cls()
                )
                router._routing_executor = executor
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        msg = f"{type(error).__name__}: {error}"
        raise RoutingFailure(msg) from error
    context = RoutingContext(
        request_id,
        model,
        messages,
        prompt_args or {},
        [
            RoutingCandidate(name, router.candidate_descriptions.get(name))
            for name in router.candidates
        ],
        router.default_model,
    )
    # Storage errors propagate outside the strategy/request failure boundary.
    decision, error = ctx.durable_execute(
        _route_once, router, context, executor, durable_id=f"route:{model}"
    )
    if error is not None:
        raise RoutingFailure(error)
    if ctx.action_metric_group is not None and decision.decision_ms is not None:
        ctx.action_metric_group.get_histogram("routingDecisionLatencyMs").update(
            int(decision.decision_ms + 0.5)
        )
    if not decision.is_abstain and decision.selected_model not in router.candidates:
        metadata = dict(decision.metadata, rejected_model=decision.selected_model)
        if decision.reason is not None:
            metadata["rejected_reason"] = decision.reason
        decision = RoutingDecision(
            reason=f"selected model '{decision.selected_model}' is not a candidate",
            metadata=metadata,
            decision_ms=decision.decision_ms,
        )
    selected = (
        (router.default_model or router.candidates[0])
        if decision.is_abstain
        else decision.selected_model
    )
    source = "default" if decision.is_abstain else "strategy"
    ctx.send_event(
        ModelRoutingEvent(
            request_id=request_id,
            router=model,
            candidates=router.candidates,
            selected_model=selected,
            decision_source=source,
            fallback_enabled=router.fallback,
            reason=decision.reason,
            score=decision.score,
            metadata=decision.metadata,
            decision_ms=decision.decision_ms,
        )
    )
    return ResolvedModelRoute(
        model,
        selected,
        list(router.candidates),
        True,
        router.fallback,
        source,
        decision.reason,
        decision.score,
        dict(decision.metadata),
    )
