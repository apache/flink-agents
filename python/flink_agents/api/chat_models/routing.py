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
"""Python declarations for select-not-delegate model routing."""

import copy
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Any, Mapping, Sequence
from uuid import UUID

from pydantic import Field, PrivateAttr, model_validator

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import Resource, ResourceDescriptor, ResourceType

RULE_PATTERN_FLAGS = re.IGNORECASE | re.ASCII


class RoutingStrategyType(str, Enum):
    """Language-neutral strategy tags shared with Java."""

    RULE_BASED = "rule_based"
    CUSTOM = "custom"
    LLM_JUDGE = "llm_judge"
    DECISION_MODEL = "decision_model"


@dataclass(frozen=True)
class RoutingStrategy:
    """A strategy declaration; execution is owned by the plan layer."""

    type: RoutingStrategyType
    arguments: Mapping[str, Any] = field(default_factory=dict)
    executor_class: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "type", RoutingStrategyType(self.type))
        object.__setattr__(
            self, "arguments", MappingProxyType(copy.deepcopy(dict(self.arguments)))
        )
        if self.type == RoutingStrategyType.DECISION_MODEL:
            msg = "Decision-model routing is not supported in Python yet."
            raise ValueError(msg)
        if self.type == RoutingStrategyType.LLM_JUDGE:
            msg = "LLM-judge routing is not supported in Python yet."
            raise ValueError(msg)
        if self.type == RoutingStrategyType.CUSTOM:
            if not isinstance(self.executor_class, str) or not self.executor_class:
                msg = "A CUSTOM routing strategy requires an executor class name."
                raise ValueError(msg)
        elif self.executor_class is not None:
            msg = "Only CUSTOM strategies carry an executor class."
            raise ValueError(msg)
        if self.type == RoutingStrategyType.RULE_BASED:
            rules = self.arguments.get("rules")
            if rules is not None:
                if not isinstance(rules, dict):
                    msg = "'rules' must be a map of candidate names to regex strings."
                    raise ValueError(msg)
                for name, pattern in rules.items():
                    if (
                        not isinstance(name, str)
                        or not name
                        or not isinstance(pattern, str)
                    ):
                        msg = (
                            "Rules require non-empty candidate names and regex strings."
                        )
                        raise ValueError(msg)
                    try:
                        re.compile(pattern, RULE_PATTERN_FLAGS)
                    except re.error as error:
                        msg = f"Invalid routing rule for '{name}': {error}"
                        raise ValueError(msg) from error


class Strategies:
    """Factories for serializable routing strategies."""

    @staticmethod
    def rules(rules: Mapping[str, str] | None) -> RoutingStrategy:
        """Select the first rule matching the latest user message."""
        return RoutingStrategy(
            RoutingStrategyType.RULE_BASED,
            {"rules": dict(rules) if rules is not None else None},
        )

    @staticmethod
    def custom(
        executor: type | str, args: Mapping[str, Any] | None = None
    ) -> RoutingStrategy:
        """Declare a named executor accepting an argument map or no arguments."""
        path = (
            executor
            if isinstance(executor, str)
            else f"{executor.__module__}.{executor.__qualname__}"
        )
        return RoutingStrategy(RoutingStrategyType.CUSTOM, args or {}, path)


@dataclass(frozen=True)
class RoutingCandidate:
    """A named chat model and its optional routing description."""

    name: str
    description: str | None = None


@dataclass(frozen=True)
class RoutingDecision:
    """A selection, or an abstention when selected_model is None."""

    selected_model: str | None = None
    reason: str | None = None
    score: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
    decision_ms: float | None = None

    @classmethod
    def of(cls, model: str) -> "RoutingDecision":
        """Select a candidate by name."""
        if not isinstance(model, str) or not model:
            msg = "A selected model must be a non-empty string."
            raise ValueError(msg)
        return cls(selected_model=model)

    @classmethod
    def abstain(cls) -> "RoutingDecision":
        """Use the router's default selection."""
        return cls()

    @property
    def is_abstain(self) -> bool:
        """Whether the strategy declined to select a model."""
        return self.selected_model is None


@dataclass(frozen=True)
class RoutingContext:
    """Snapshot passed to strategies, without model invocation capabilities."""

    request_id: UUID
    router: str
    messages: Sequence[ChatMessage]
    prompt_args: Mapping[str, Any]
    candidates: Sequence[RoutingCandidate]
    default_model: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "messages", tuple(copy.deepcopy(self.messages)))
        object.__setattr__(
            self, "prompt_args", MappingProxyType(copy.deepcopy(dict(self.prompt_args)))
        )
        object.__setattr__(self, "candidates", tuple(self.candidates))

    def last_user_message(self) -> str:
        """Return the latest user text, or an empty string."""
        return next(
            (m.text for m in reversed(self.messages) if m.role == MessageRole.USER), ""
        )

    def first_user_message(self) -> str:
        """Return the first user text, or an empty string."""
        return next((m.text for m in self.messages if m.role == MessageRole.USER), "")


class CustomRoutingExecutor(ABC):
    """Select a model; the framework invokes it and handles tools and retries."""

    @abstractmethod
    def route(
        self, strategy: RoutingStrategy, context: RoutingContext
    ) -> RoutingDecision:
        """Return a selection or abstention."""


class ModelRouter(Resource):
    """Router resource using the same descriptor argument keys as Java."""

    _routing_executor: Any = PrivateAttr(default=None)

    candidates: list[str]
    candidate_descriptions: dict[str, str] = Field(default_factory=dict)
    default_model: str | None = None
    fallback: bool = False
    strategy_type: str
    strategy_args: dict[str, Any] = Field(default_factory=dict)
    strategy_executor_class: str | None = None

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return the router resource type."""
        return ResourceType.MODEL_ROUTER

    @property
    def strategy(self) -> RoutingStrategy:
        """Return the validated strategy declaration."""
        return RoutingStrategy(
            self.strategy_type, self.strategy_args, self.strategy_executor_class
        )

    @model_validator(mode="after")
    def _validate_router(self) -> "ModelRouter":
        if not self.candidates or any(not name for name in self.candidates):
            msg = "A model router requires non-empty candidate names."
            raise ValueError(msg)
        if len(set(self.candidates)) != len(self.candidates):
            msg = "A model router must not declare duplicate candidates."
            raise ValueError(msg)
        if self.default_model is not None and self.default_model not in self.candidates:
            msg = "The default model must be a candidate."
            raise ValueError(msg)
        strategy = self.strategy
        if strategy.type == RoutingStrategyType.RULE_BASED:
            for name in strategy.arguments.get("rules") or {}:
                if name not in self.candidates:
                    msg = f"Rule model '{name}' is not a candidate."
                    raise ValueError(msg)
        return self

    @staticmethod
    def of(*candidates: str) -> "ModelRouterBuilder":
        """Start a descriptor builder with candidates in fallback order."""
        return ModelRouterBuilder(list(candidates))


class ModelRouterBuilder:
    """Build a serializable ModelRouter descriptor."""

    def __init__(self, candidates: list[str]) -> None:
        """Start with the declared candidate order."""
        self._args = {"candidates": candidates, "fallback": False}

    def strategy(self, strategy: RoutingStrategy) -> "ModelRouterBuilder":
        """Set the strategy declaration."""
        self._args.update(
            strategy_type=strategy.type.value,
            strategy_args=copy.deepcopy(dict(strategy.arguments)),
        )
        if strategy.executor_class is not None:
            self._args["strategy_executor_class"] = strategy.executor_class
        else:
            self._args.pop("strategy_executor_class", None)
        return self

    def describe(self, candidate: str, description: str) -> "ModelRouterBuilder":
        """Describe a candidate for model-based routing."""
        self._args.setdefault("candidate_descriptions", {})[candidate] = description
        return self

    def default_model(self, model: str) -> "ModelRouterBuilder":
        """Choose the candidate used when a strategy abstains."""
        self._args["default_model"] = model
        return self

    def fallback(self, *, enabled: bool) -> "ModelRouterBuilder":
        """Try remaining candidates after the selected model exhausts retries."""
        self._args["fallback"] = enabled
        return self

    def build(self) -> ResourceDescriptor:
        """Validate and return a Python resource descriptor."""
        ModelRouter(**self._args)
        return ResourceDescriptor(
            clazz=f"{ModelRouter.__module__}.ModelRouter",
            arguments=copy.deepcopy(self._args),
        )
