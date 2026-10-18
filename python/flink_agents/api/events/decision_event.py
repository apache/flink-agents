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
"""Request and terminal response events for registered decision model resources."""

import json
from collections.abc import Mapping
from typing import ClassVar, Tuple
from uuid import UUID

from pydantic import JsonValue
from typing_extensions import override

from flink_agents.api.decision_models import DecisionRequest, DecisionResult
from flink_agents.api.decision_models.decision_model import _validate_request_params
from flink_agents.api.events.event import BuiltInAttribute, Event


class DecisionRequestEvent(Event):
    """Request a decision from a registered DecisionModel setup resource.

    ``model`` is the registered resource name, not a provider model identifier.
    ``request`` supplies the shared input and named questions for this call.
    ``request_params`` supplies provider-specific parameters for this call.
    """

    EVENT_TYPE: ClassVar[str] = "_decision_request_event"
    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required("model", str),
        BuiltInAttribute.required_untyped("request"),
        BuiltInAttribute.optional_untyped("request_params"),
    )

    def __init__(
        self,
        model: str,
        request: DecisionRequest,
        *,
        request_params: Mapping[str, JsonValue] | None = None,
    ) -> None:
        """Create a decision request event."""
        if not isinstance(model, str) or not isinstance(request, DecisionRequest):
            msg = "Decision request requires a model resource name and DecisionRequest."
            raise TypeError(msg)
        super().__init__(
            type=self.EVENT_TYPE,
            attributes={
                "model": model,
                "request": request,
                "request_params": _validate_request_params(request_params),
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "DecisionRequestEvent":
        attributes = event.attributes
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, attributes, cls._ATTRIBUTE_SCHEMA
        )
        result = cls(
            model=attributes["model"],
            request=DecisionRequest.model_validate(attributes["request"]),
            request_params=attributes.get("request_params"),
        )
        return result.reconstruct_from(event)

    @property
    def model(self) -> str:
        """Return the registered decision model resource name."""
        return self.get_attr("model")

    @property
    def request(self) -> DecisionRequest:
        """Return the typed request with its input and named questions."""
        return self.get_attr("request")

    @property
    def request_params(self) -> dict[str, JsonValue]:
        """Return provider-specific parameters for this request."""
        return self.get_attr("request_params")


class DecisionResponseError(RuntimeError):
    """A failed decision result, retaining the request ID and textual error."""

    def __init__(self, request_id: UUID, error: str) -> None:
        """Keep the request ID and textual provider error."""
        super().__init__(error)
        self.request_id = request_id


class DecisionResponseEvent(Event):
    """Event representing a response from decision model.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    response : DecisionResult
        The response from the decision model.
    retry_count : int
        The total number of retries for this request.
    total_retry_wait_sec : int
        The total time spent waiting during retries in seconds.
    """

    EVENT_TYPE: ClassVar[str] = "_decision_response_event"
    SUCCESS: ClassVar[str] = "SUCCESS"
    FAILED: ClassVar[str] = "FAILED"

    _REQUEST_ID: ClassVar[str] = "request_id"
    _STATUS: ClassVar[str] = "status"
    _RESPONSE: ClassVar[str] = "response"
    _ERROR: ClassVar[str] = "error"
    _RETRY_COUNT: ClassVar[str] = "retry_count"
    _TOTAL_RETRY_WAIT_SEC: ClassVar[str] = "total_retry_wait_sec"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_untyped(_REQUEST_ID),
        BuiltInAttribute.required_untyped(_STATUS),
        BuiltInAttribute.optional_untyped(_RESPONSE),
        BuiltInAttribute.optional_untyped(_ERROR),
        BuiltInAttribute.optional(_RETRY_COUNT, int),
        BuiltInAttribute.optional(_TOTAL_RETRY_WAIT_SEC, int),
    )

    def __init__(
        self,
        request_id: UUID,
        status: str,
        response: DecisionResult | None = None,
        error: str | None = None,
        retry_count: int = 0,
        total_retry_wait_sec: int = 0,
    ) -> None:
        """Create a DecisionResponseEvent."""
        if not (
            (
                status == self.SUCCESS
                and isinstance(response, DecisionResult)
                and error is None
            )
            or (
                status == self.FAILED
                and response is None
                and isinstance(error, str)
                and error
            )
        ):
            msg = (
                "Decision response requires SUCCESS with response or FAILED with error."
            )
            raise ValueError(msg)
        request_id = UUID(request_id) if isinstance(request_id, str) else request_id
        if not isinstance(request_id, UUID):
            msg = "request_id must be a UUID"
            raise TypeError(msg)
        super().__init__(
            type=DecisionResponseEvent.EVENT_TYPE,
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
        response: DecisionResult,
        retry_count: int = 0,
        total_retry_wait_sec: int = 0,
    ) -> "DecisionResponseEvent":
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
    ) -> "DecisionResponseEvent":
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
    def from_event(cls, event: Event) -> "DecisionResponseEvent":
        attributes = event.attributes
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, attributes, cls._ATTRIBUTE_SCHEMA
        )
        response_raw = attributes.get(cls._RESPONSE)
        response = (
            DecisionResult.model_validate_json(
                json.dumps(response_raw, ensure_ascii=False, allow_nan=False)
            )
            if isinstance(response_raw, dict)
            else response_raw
        )
        result = DecisionResponseEvent(
            request_id=attributes[cls._REQUEST_ID],
            status=attributes[cls._STATUS],
            response=response,
            error=attributes.get(cls._ERROR),
            retry_count=attributes.get(cls._RETRY_COUNT, 0),
            total_retry_wait_sec=attributes.get(cls._TOTAL_RETRY_WAIT_SEC, 0),
        )
        return result.reconstruct_from(event)

    @property
    def request_id(self) -> UUID:
        """Return the request event ID."""
        val = self.get_attr(self._REQUEST_ID)
        return UUID(val) if isinstance(val, str) else val

    @property
    def response(self) -> DecisionResult:
        """Return the decision model response."""
        if self.is_failed:
            raise DecisionResponseError(self.request_id, self.error)
        return self.get_attr(self._RESPONSE)

    @property
    def status(self) -> str:
        """Return the terminal status."""
        return self.get_attr(self._STATUS)

    @property
    def is_success(self) -> bool:
        """Whether the decision succeeded."""
        return self.status == self.SUCCESS

    @property
    def is_failed(self) -> bool:
        """Whether the decision failed."""
        return self.status == self.FAILED

    @property
    def error(self) -> str:
        """Return failure text; a successful event has no error."""
        if not self.is_failed:
            msg = "A successful decision response has no error."
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
