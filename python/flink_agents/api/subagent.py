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
import json
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, field_validator, model_validator

from flink_agents.api.resource import ResourceType, SerializableResource

if TYPE_CHECKING:
    from flink_agents.api.runner_context import RunnerContext

_LOG = logging.getLogger(__name__)

# Prefix of the callable name a sub-agent is exposed to a chat model under.
# Tools are forbidden to register under this prefix, so a prefixed callable
# name unambiguously addresses a sub-agent and the executing side routes it to
# the AGENT namespace.
CALLABLE_NAME_PREFIX = "subagent_"


def _input_schema_from(input_type: type) -> str | None:
    """Render the schema of ``input_type``, or None when it states no shape.

    ``object`` is what a sub-agent declares when it declares none, and it has no
    fields for a model to build a call from. A type that is not a pydantic model
    is refused rather than dropped: it is a declaration mistake, and the caller
    has two ways to fix it.

    Args:
        input_type: The declared type of the arguments.

    Returns:
        The schema as a JSON string, the form the cross-language plan carries,
        or None when the type states no shape.

    Raises:
        TypeError: If ``input_type`` is not a pydantic model, or has no JSON
            Schema.
    """
    if input_type is object:
        return None
    if not (isinstance(input_type, type) and issubclass(input_type, BaseModel)):
        msg = (
            f"Sub-agent input type {input_type} is not supported. Declare a"
            " pydantic BaseModel, or declare an input schema explicitly."
        )
        raise TypeError(msg)
    try:
        schema = input_type.model_json_schema()
    except Exception as e:
        msg = (
            f"Sub-agent input type {input_type.__module__}"
            f".{input_type.__qualname__} cannot be rendered as a JSON Schema, so"
            " it cannot be declared to a chat model. Declare an input schema"
            " explicitly, or use an input type whose fields are all"
            f" JSON-Schema-renderable. Rendering it reported: {e}"
        )
        raise TypeError(msg) from e
    return json.dumps(schema)


@dataclass
class SubagentResult:
    """Outcome of a sub-agent call issued through :class:`SubagentSetup`.

    A successful outcome carries a JSON-serializable ``result``, and a failed
    one carries a serializable ``error_message``.

    Implementations capture their internal failures into a result through
    :meth:`error` instead of raising, so callers inspect ``success`` rather
    than catching. Because the failure is carried as a message rather than a
    live exception, the whole result can be persisted through durable
    execution and survive a failover.
    """

    success: bool
    result: Any = None
    error_message: str | None = None

    @staticmethod
    def ok(result: Any) -> "SubagentResult":
        """Create a successful result carrying ``result``."""
        return SubagentResult(success=True, result=result)

    @staticmethod
    def error(error: BaseException | str) -> "SubagentResult":
        """Create a failed result from an exception or a plain message.

        For an exception the exception's type and message are stored as a
        serializable string so the result can survive durable execution. The
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
        return SubagentResult(success=False, error_message=message)

    @property
    def exception(self) -> Exception | None:
        """Reconstruct an exception carrying the stored summary; None on success."""
        return None if self.success else RuntimeError(self.error_message)


class SubagentFuture(ABC):
    """Handle for one sub-agent invocation, identified by the
    ``(session_id, call_id)`` pair that keys the invocation.
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
        """Request cancellation of the invocation."""

    @abstractmethod
    def combine(self, *others: "SubagentFuture") -> "SubagentFutures":
        """Group this handle with others to be resolved together."""

    @abstractmethod
    def __await__(self) -> Any:
        """Resolve the invocation, waiting until it reaches a terminal state.

        Failures converge into a failed :class:`SubagentResult` rather than a
        separately raised exception.
        """


class SubagentFutures(ABC):
    """A group of sub-agent handles to be resolved together.

    A group is not itself an invocation and carries no
    ``(session_id, call_id)`` identity.
    """

    @abstractmethod
    def done(self) -> bool:
        """Whether every handle in the group has been resolved."""

    def cancel(self) -> None:  # noqa: B027 - deliberate no-op default
        """Propagate the cancellation request to every handle in the group."""

    @abstractmethod
    def combine(self, *others: "SubagentFuture") -> "SubagentFutures":
        """Add more handles to the group."""

    @abstractmethod
    def __await__(self) -> Any:
        """Resolve every handle in the group and return their outcomes in the
        order the handles were added. Like awaiting a single handle, failures
        surface through failed :class:`SubagentResult`s.
        """


class SubagentSetup(SerializableResource):
    """Caller-facing definition of a sub-agent, registered as an AGENT resource.

    Attributes:
    ----------
    description : str
        Tells a caller what this sub-agent is for, so that it can decide
        whether to delegate to it. This is routing information for the caller,
        not an instruction for the sub-agent itself.
    input_schema : str | None
        JSON Schema of the arguments this sub-agent accepts, as declared
        explicitly. Derived from :meth:`input_type` when it is not, and left
        ``None`` when neither says anything a model could build a call from.
    """

    description: str = ""
    input_schema: str | None = None

    @field_validator("input_schema")
    @classmethod
    def __check_input_schema(cls, input_schema: str | None) -> str | None:
        if input_schema is not None and not input_schema.strip():
            msg = "Sub-agent input schema must not be blank."
            raise ValueError(msg)
        return input_schema

    @model_validator(mode="after")
    def __derive_input_schema(self) -> "SubagentSetup":
        """Fill an undeclared schema from the input type.

        Filled at construction rather than read on demand, so that what a caller
        reads off this setup and what the plan carries are the same schema.
        """
        if self.input_schema is None:
            self.input_schema = _input_schema_from(type(self).input_type())
        return self

    @classmethod
    def input_type(cls) -> type:
        """The type of the arguments this sub-agent accepts.

        Override to type the arguments instead of spelling out their schema;
        :class:`object`, the default, states no shape, which leaves the
        sub-agent undeclared to a chat model unless an input schema is given.
        """
        return object

    @classmethod
    def result_type(cls) -> type:
        """The type the result is converted to before it is reported back.

        Override to state the shape of a result that is not already
        JSON-compatible, or to narrow a wider one; :class:`object`, the
        default, reports the result as it arrived.
        """
        return object

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.AGENT

    @abstractmethod
    async def submit(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> SubagentFuture:
        """Issue one invocation and return its handle.

        Declared ``async`` to reserve the ability to await while the request
        is being issued, so that one calling form holds whether or not an
        implementation has anything to await.

        Without ids, the implementation assigns the identity and starts a
        fresh conversation. This is the preferred form.

        Pass ``session_id`` to continue the conversation of an earlier
        invocation. The session id is available on the handle returned by
        that invocation. Whether a conversation can be continued across
        actions is up to the concrete implementation.

        The complete ``(session_id, call_id)`` identity is reserved for
        implementation use.
        """
