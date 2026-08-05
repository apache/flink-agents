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
#  limitations under the License.
#################################################################################
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from flink_agents.api.resource import ResourceType, SerializableResource

if TYPE_CHECKING:
    from flink_agents.api.runner_context import RunnerContext

_LOG = logging.getLogger(__name__)


@dataclass
class Result:
    """Outcome of a sub-agent call.

    A successful call carries a JSON-serializable ``result``; a failed call
    carries a serializable ``error_message`` — the exception's type and
    message — rather than a live exception, so a result can be persisted
    through durable execution and survive failover. The full stack trace is
    logged when the failure is captured, not persisted.
    """

    success: bool
    result: Any = None
    error_message: str | None = None

    @staticmethod
    def ok(result: Any) -> "Result":
        """Create a successful result carrying ``result``."""
        return Result(success=True, result=result)

    @staticmethod
    def error(error: BaseException | str) -> "Result":
        """Create a failed result from an exception or a plain message.

        For an exception the exception's type and message are stored as a
        serializable string so the result can survive durable execution; the
        full stack trace is logged here rather than persisted, keeping the
        durable payload bounded.
        """
        if isinstance(error, BaseException):
            _LOG.warning(
                "Sub-agent call failed; persisting the exception summary.",
                exc_info=(
                    type(error),
                    error,
                    error.__traceback__,
                ),
            )
            message = f"{type(error).__name__}: {error}"
        else:
            message = error
        return Result(success=False, error_message=message)

    @property
    def exception(self) -> Exception | None:
        """Reconstruct an exception carrying the stored summary; None on success."""
        return None if self.success else RuntimeError(self.error_message)


class SubagentFuture(ABC):
    """Handle for one sub-agent invocation.

    Carries the ``(session_id, call_id)`` identity that keys the invocation's
    durable state. The handle is a driver of the invocation's lifecycle, not
    a passive view: the deferred request is issued when the handle is first
    resolved (the resolve is the trigger), the wait drives the durable state
    machine forward, and a cancellation decides whether the request is ever
    sent. Several handles can be grouped through :meth:`combine` for a
    batched resolve in submission order.

    Resolving is await-only: ``await future`` is the single entry point, and
    the wait releases the mailbox so the surrounding operator keeps making
    progress; there is no synchronous resolve.

    Abstract data structure; the implementations live in the runtime layer.
    """

    def __init__(self, session_id: str, call_id: str) -> None:
        """Initialize with the invocation identity."""
        self._session_id = session_id
        self._call_id = call_id

    @property
    def session_id(self) -> str:
        """The session this invocation belongs to."""
        return self._session_id

    @property
    def call_id(self) -> str:
        """The id of this invocation within its session."""
        return self._call_id

    @property
    def identity(self) -> str:
        """The ``session_id#call_id`` string keying this invocation."""
        return f"{self._session_id}#{self._call_id}"

    @abstractmethod
    def done(self) -> bool:
        """Whether the invocation has been resolved."""

    def cancel(self) -> None:  # noqa: B027 - deliberate no-op default
        """Request cancellation of the invocation.

        The default implementation does nothing; the cancellation semantics
        are defined by the concrete implementation.
        """

    @abstractmethod
    def combine(self, *others: "SubagentFuture") -> "SubagentFutures":
        """Group this handle with others for a batched resolve."""

    @abstractmethod
    def __await__(self) -> Any:
        """Resolve the invocation, releasing the mailbox while waiting."""


class SubagentFutures(ABC):
    """Several sub-agent handles resolved together, in submission order.

    Each handle resolves itself when its wait starts; each invocation keeps
    its own durable slot, so a partially completed batch replays the parts
    that finished.

    Usage::

        results = await first.combine(second, third)

    A batch is not an invocation: it carries no ``(session_id, call_id)``
    identity and is not a :class:`SubagentFuture`, so it resolves only
    through ``await`` on the group itself.

    Abstract data structure; the implementations live in the runtime layer.
    """

    @abstractmethod
    def done(self) -> bool:
        """Whether every handle in the batch has been resolved."""

    def cancel(self) -> None:  # noqa: B027 - deliberate no-op default
        """Propagate the cancellation request to every handle in the batch.

        The default implementation does nothing; the cancellation semantics
        are defined by the concrete implementation.
        """

    @abstractmethod
    def combine(self, *others: "SubagentFuture") -> "SubagentFutures":
        """Add more handles to the batch."""

    @abstractmethod
    def __await__(self) -> Any:
        """Resolve every handle in submission order, releasing the mailbox."""


class Subagent(ABC):
    """Caller-facing interface for a sub-agent invocable from within an action.

    An invocation is identified by a ``(session_id, call_id)`` pair; the
    session groups a conversation across invocations.

    Callers do not manage ids: :meth:`submit` leaves the missing ids to the
    implementation, which assigns them (runtime setups typically through a
    deterministic id allocator) or rejects the call. The full form taking
    the complete identity is the implementation-side contract declared by
    :class:`SubagentSetup`; resolving the returned handle is ``await``.
    """

    @abstractmethod
    def submit(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
    ) -> SubagentFuture:
        """Issue one invocation and return its handle.

        With ``session_id`` given, the implementation picks the call id;
        without it, the implementation picks the whole identity.
        """


class SubagentSetup(Subagent, SerializableResource):
    """Base descriptor for a sub-agent resource, registered as an AGENT resource.

    Declares the full ``submit`` form taking the complete
    ``(session_id, call_id)`` identity — the implementation-side contract
    outside the caller-facing short forms of :class:`Subagent`. The api
    layer only declares the resource shape: invocation behavior (deferred
    handles and the callable for one invocation) and any id assignment
    backing the short forms live in the runtime layer's sub-agent setup
    bases, which extend this class.
    """

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.AGENT

    @abstractmethod
    def submit(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Issue one invocation under the given identity and return its
        handle.
        """
