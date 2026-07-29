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
import traceback
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Callable, Generic, TypeVar

from flink_agents.api.resource import ResourceType, SerializableResource

if TYPE_CHECKING:
    from flink_agents.api.runner_context import RunnerContext

T = TypeVar("T")


@dataclass
class Result:
    """Outcome of a sub-agent call.

    A successful call carries a JSON-serializable ``result``; a failed call
    carries a serializable ``error_message`` — the full stack trace of the
    failure — rather than a live exception, so a result can be persisted
    through durable execution and survive failover.
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

        For an exception the full stack trace is stored as a serializable
        string so the result can survive durable execution.
        """
        if isinstance(error, BaseException):
            message = "".join(
                traceback.format_exception(type(error), error, error.__traceback__)
            )
        else:
            message = error
        return Result(success=False, error_message=message)

    @property
    def exception(self) -> Exception | None:
        """Reconstruct an exception carrying the stored stack trace; None on success."""
        return None if self.success else RuntimeError(self.error_message)


@dataclass
class DurableCallable(Generic[T]):
    """A callable for durable execution that carries a stable identifier.

    Used with :meth:`RunnerContext.durable_execute` and
    :meth:`RunnerContext.durable_execute_async` so each durable call has a
    stable id that persists across job restarts.
    """

    id: str
    call: Callable[[], T]
    reconciler: Callable[[], T] | None = field(default=None)


class BaseSubagentCallable(DurableCallable["Result"], ABC):
    """Convenience base for the callable returned by ``as_async_callable``.

    Keys the durable call by the framework-assigned identity as
    ``session_id#call_id`` (the :class:`SubagentSetup` contract) and captures
    exceptions raised by :meth:`call_internal` into :meth:`Result.error`, so
    failures are reported through the result rather than raised.
    Implementations only provide :meth:`call_internal`.
    """

    def __init__(self, session_id: str, call_id: str) -> None:
        """Initialize with the framework-assigned identity."""
        super().__init__(id=f"{session_id}#{call_id}", call=self._invoke)

    def _invoke(self) -> "Result":
        try:
            return Result.ok(self.call_internal())
        except Exception as e:
            return Result.error(e)

    @abstractmethod
    def call_internal(self) -> Any:
        """Perform the invocation and return the JSON-serializable payload.

        Raised exceptions are captured into a failed :class:`Result`.
        """


class Subagent(ABC):
    """Caller-facing interface for a sub-agent invocable from within an action.

    An invocation is identified by a ``(session_id, call_id)`` pair; the
    session groups a conversation across invocations. Both ids are assigned by
    the framework (:meth:`RunnerContext.next_session_id` and
    :meth:`RunnerContext.next_call_id`): callers may supply a session id to
    continue a prior session but never supply a call id.
    """

    @abstractmethod
    def call(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
    ) -> Result:
        """Synchronously invoke the sub-agent and return its :class:`Result`.

        An omitted ``session_id`` starts a new session.
        """

    @abstractmethod
    def as_async_callable(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
    ) -> DurableCallable[Result]:
        """Return the deferred :class:`DurableCallable` for one invocation.

        An omitted ``session_id`` starts a new session.
        """


class SubagentSetup(SerializableResource, Subagent, ABC):
    """Base setup for a sub-agent resource, registered as an AGENT resource.

    Hosts the id-resolution chain behind :class:`Subagent`: omitted ids are
    assigned via :meth:`RunnerContext.next_session_id` and
    :meth:`RunnerContext.next_call_id` before an implementation is ever
    invoked, and :meth:`call` runs the deferred callable through durable
    execution. Implementations only provide the terminal 4-arg
    :meth:`as_async_callable` and contribute nothing to identity assignment.
    """

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.AGENT

    def call(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> Result:
        """Synchronously invoke the sub-agent and return its :class:`Result`.

        Omitted ids are assigned from the context (``call_id`` is
        framework-facing and never supplied by callers). The deferred callable
        runs through durable execution, so the invocation participates in
        failover recovery.
        """
        if session_id is None:
            session_id = ""
        if call_id is None:
            call_id = ""
        callable_ = self.as_async_callable(ctx, prompt, session_id, call_id)
        return ctx.durable_execute(
            callable_.call, reconciler=callable_.reconciler
        )

    @abstractmethod
    def as_async_callable(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> DurableCallable[Result]:
        """Return the deferred :class:`DurableCallable` for one invocation.

        Implementations resolve ``None`` ids via ``ctx.next_session_id()`` /
        ``ctx.next_call_id(session_id)``; :meth:`call` always passes resolved
        ids. Contract: the returned callable's ``id`` MUST be derived solely
        from the ``(session_id, call_id)`` pair (extend
        :class:`BaseSubagentCallable` to get this for free).
        """
