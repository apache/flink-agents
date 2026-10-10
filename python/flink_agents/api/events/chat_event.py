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
from typing import Any, ClassVar, Dict, List, Tuple

try:
    from typing import override
except ImportError:
    from typing_extensions import override
from uuid import UUID

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.events.event import BuiltInAttribute, Event


class ChatRequestEvent(Event):
    """Event representing a request to chat model.

    Attributes:
    ----------
    model : str
        The name of the chat model to be chatted with.
    messages : List[ChatMessage]
        The input to the chat model.
    prompt_args : Dict[str, Any]
        Variables used to fill the chat model's prompt template, if a prompt
        resource is configured on the chat model setup. Empty by default.
    output_schema: OutputSchema | None
        The expected output schema of the chat model final response. Optional.
    """

    EVENT_TYPE: ClassVar[str] = "_chat_request_event"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required("model", str),
        BuiltInAttribute.required_list(
            "messages", "a ChatMessage or its serialized dict", (ChatMessage, dict)
        ),
        BuiltInAttribute.optional("prompt_args", dict),
        BuiltInAttribute.optional_untyped("output_schema"),
    )

    def __init__(
        self,
        model: str,
        messages: List[ChatMessage],
        prompt_args: Dict[str, Any] | None = None,
        output_schema: OutputSchema | None = None,
    ) -> None:
        """Create a ChatRequestEvent."""
        super().__init__(
            type=ChatRequestEvent.EVENT_TYPE,
            attributes={
                "model": model,
                "messages": messages,
                "prompt_args": prompt_args if prompt_args is not None else {},
                "output_schema": output_schema,
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ChatRequestEvent":
        attributes = event.attributes
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, attributes, cls._ATTRIBUTE_SCHEMA
        )
        messages = [
            ChatMessage.model_validate(m) if isinstance(m, dict) else m
            for m in attributes["messages"]
        ]
        output_schema_raw = attributes.get("output_schema")
        if isinstance(output_schema_raw, dict):
            output_schema_raw = OutputSchema.model_validate(output_schema_raw)
        result = ChatRequestEvent(
            model=attributes["model"],
            messages=messages,
            prompt_args=attributes.get("prompt_args"),
            output_schema=output_schema_raw,
        )
        return result.reconstruct_from(event)

    @property
    def model(self) -> str:
        """Return the chat model name."""
        return self.get_attr("model")

    @property
    def messages(self) -> List[ChatMessage]:
        """Return the chat messages."""
        return self.get_attr("messages")

    @property
    def prompt_args(self) -> Dict[str, Any]:
        """Return the prompt-template arguments, empty if not set."""
        args = self.get_attr("prompt_args")
        return args if args is not None else {}

    @property
    def output_schema(self) -> OutputSchema | None:
        """Return the expected output schema, if any."""
        return self.get_attr("output_schema")


class ChatResponseError(RuntimeError):
    """A failed chat result, retaining the request ID and textual error."""

    def __init__(self, request_id: UUID, error: str) -> None:
        """Keep the request ID and textual provider error."""
        super().__init__(error)
        self.request_id = request_id


class ChatResponseEvent(Event):
    """Event representing a response from chat model.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    response : ChatResult
        The response from the chat model.
    retry_count : int
        The total number of retries across all tool call rounds.
    total_retry_wait_sec : int
        The total time spent waiting during retries in seconds.
    """

    EVENT_TYPE: ClassVar[str] = "_chat_response_event"
    SUCCESS: ClassVar[str] = "SUCCESS"
    FAILED: ClassVar[str] = "FAILED"

    _REQUEST_ID: ClassVar[str] = "request_id"
    _STATUS: ClassVar[str] = "status"
    _RESPONSE: ClassVar[str] = "response"
    _ERROR: ClassVar[str] = "error"
    _RETRY_COUNT: ClassVar[str] = "retry_count"
    _TOTAL_RETRY_WAIT_SEC: ClassVar[str] = "total_retry_wait_sec"
    _STRUCTURED_OUTPUT: ClassVar[str] = "structured_output"
    _MODEL_ROUTING: ClassVar[str] = "model_routing"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_untyped(_REQUEST_ID),
        BuiltInAttribute.required_untyped(_STATUS),
        BuiltInAttribute.optional_untyped(_RESPONSE),
        BuiltInAttribute.optional_untyped(_ERROR),
        BuiltInAttribute.optional(_RETRY_COUNT, int),
        BuiltInAttribute.optional(_TOTAL_RETRY_WAIT_SEC, int),
        BuiltInAttribute.optional_untyped(_STRUCTURED_OUTPUT),
        BuiltInAttribute.optional(_MODEL_ROUTING, dict),
    )

    def __init__(
        self,
        request_id: UUID,
        status: str,
        response: ChatResult | None = None,
        error: str | None = None,
        retry_count: int = 0,
        total_retry_wait_sec: int = 0,
    ) -> None:
        """Create a ChatResponseEvent."""
        if not (
            (
                status == self.SUCCESS
                and isinstance(response, ChatResult)
                and error is None
            )
            or (
                status == self.FAILED
                and response is None
                and isinstance(error, str)
                and error
            )
        ):
            msg = "Chat response requires SUCCESS with response or FAILED with error."
            raise ValueError(msg)
        request_id = UUID(request_id) if isinstance(request_id, str) else request_id
        if not isinstance(request_id, UUID):
            msg = "request_id must be a UUID"
            raise TypeError(msg)
        super().__init__(
            type=ChatResponseEvent.EVENT_TYPE,
            attributes={
                self._REQUEST_ID: request_id,
                self._STATUS: status,
                self._RESPONSE: response,
                self._ERROR: error,
                self._RETRY_COUNT: retry_count,
                self._TOTAL_RETRY_WAIT_SEC: total_retry_wait_sec,
            },
        )

    @classmethod
    def success(
        cls,
        request_id: UUID,
        response: ChatResult,
        retry_count: int = 0,
        total_retry_wait_sec: int = 0,
    ) -> "ChatResponseEvent":
        """Create a successful terminal response."""
        return cls(
            request_id,
            cls.SUCCESS,
            response=response,
            retry_count=retry_count,
            total_retry_wait_sec=total_retry_wait_sec,
        )

    @classmethod
    def failed(
        cls,
        request_id: UUID,
        error: str,
        retry_count: int = 0,
        total_retry_wait_sec: int = 0,
    ) -> "ChatResponseEvent":
        """Create a failed terminal response."""
        return cls(
            request_id,
            cls.FAILED,
            error=error,
            retry_count=retry_count,
            total_retry_wait_sec=total_retry_wait_sec,
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ChatResponseEvent":
        attributes = event.attributes
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, attributes, cls._ATTRIBUTE_SCHEMA
        )
        response_raw = attributes.get(cls._RESPONSE)
        response = (
            ChatResult.model_validate(response_raw)
            if isinstance(response_raw, dict)
            else response_raw
        )
        result = ChatResponseEvent(
            request_id=attributes[cls._REQUEST_ID],
            status=attributes[cls._STATUS],
            response=response,
            error=attributes.get(cls._ERROR),
            retry_count=attributes.get(cls._RETRY_COUNT, 0),
            total_retry_wait_sec=attributes.get(cls._TOTAL_RETRY_WAIT_SEC, 0),
        )
        result.attributes[cls._STRUCTURED_OUTPUT] = event.attributes.get(
            cls._STRUCTURED_OUTPUT
        )
        if cls._MODEL_ROUTING in event.attributes:
            result.attributes[cls._MODEL_ROUTING] = event.attributes[cls._MODEL_ROUTING]
        return result.reconstruct_from(event)

    @property
    def request_id(self) -> UUID:
        """Return the request event ID."""
        val = self.get_attr(self._REQUEST_ID)
        return UUID(val) if isinstance(val, str) else val

    @property
    def response(self) -> ChatResult:
        """Return the chat model response."""
        if self.is_failed:
            raise ChatResponseError(self.request_id, self.error)
        return self.get_attr(self._RESPONSE)

    @property
    def structured_output(self) -> Any:
        """Parsed final output, separate from the provider response."""
        return self.attributes.get(self._STRUCTURED_OUTPUT)

    @property
    def status(self) -> str:
        """Return the terminal status."""
        return self.get_attr(self._STATUS)

    @property
    def is_success(self) -> bool:
        """Whether the chat succeeded."""
        return self.status == self.SUCCESS

    @property
    def is_failed(self) -> bool:
        """Whether the chat failed."""
        return self.status == self.FAILED

    @property
    def error(self) -> str:
        """Return failure text; a successful event has no error."""
        if not self.is_failed:
            msg = "A successful chat response has no error."
            raise RuntimeError(msg)
        return self.get_attr(self._ERROR)

    @property
    def retry_count(self) -> int:
        """Return the total number of retries."""
        return self.get_attr(self._RETRY_COUNT)

    @property
    def total_retry_wait_sec(self) -> int:
        """Return the total retry wait time in seconds."""
        return self.get_attr(self._TOTAL_RETRY_WAIT_SEC)
