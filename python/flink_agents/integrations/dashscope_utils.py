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
"""Shared DashScope utility functions."""

import math
import os
from collections.abc import Collection, Iterator, Mapping
from contextlib import contextmanager
from threading import Lock
from typing import Annotated, Any
from urllib.parse import urlsplit

import dashscope
from dashscope.errors import DashScopeError
from pydantic import BaseModel, Field, PrivateAttr, TypeAdapter

from flink_agents.api.chat_message import (
    Base64Source,
    MediaBlock,
    UnsupportedContentBlockError,
    UrlSource,
)

_HTTP_SCHEMES = frozenset({"http", "https"})
_UNSUPPORTED_CALL_OPTIONS = frozenset(
    {
        # Removed 1.x call options.
        "api_protocol",
        "base_address",
        "session",
        "http_method",
        "enable_encryption",
        "query",
        "form",
        "resources",
        "flattened_output",
        "extra_url_parameters",
        "is_service",
        "user_agent",
        "task_id",
        "async_request",
        "ws_stream_mode",
        "is_binary_input",
        "pre_task_id",
        "sdk_module",
        # SDK client options must not leak into the model parameters.
        "timeout",
        "max_retries",
        "extra_headers",
        "extra_query",
        "extra_body",
        "raw",
    }
)


def merge_model_kwargs(
    explicit_fields: Mapping[str, Any],
    additional_kwargs: Mapping[str, Any],
) -> dict[str, Any]:
    """Merge non-None explicit setup fields with ``additional_kwargs`` overrides."""
    return {
        **{key: value for key, value in explicit_fields.items() if value is not None},
        **additional_kwargs,
    }


def media_source_url(
    block: MediaBlock,
    *,
    allowed_schemes: Collection[str] = _HTTP_SCHEMES,
) -> str:
    """Return an allowed media URL or encode Base64 content as an inline data URI."""
    source = block.source
    if isinstance(source, UrlSource):
        if urlsplit(source.url).scheme not in allowed_schemes:
            provider = "DashScope"
            schemes = ", ".join(sorted(allowed_schemes))
            reason = f"media URL scheme must be one of: {schemes}"
            raise UnsupportedContentBlockError.for_block(provider, block, reason)
        return source.url
    if isinstance(source, Base64Source):
        return f"data:{block.media_type};base64,{source.data}"
    msg = "A media block requires a URL or Base64 source."
    raise TypeError(msg)


def create_client(
    *,
    api_key: str,
    base_url: str | None,
    workspace: str | None,
    region: str,
    timeout: float,
    protocol: str | None = None,
) -> dashscope.Client:
    """Create a client that does not read local config and does not retry."""
    return dashscope.Client(
        api_key=api_key,
        base_url=base_url,
        workspace=workspace,
        region=region,
        timeout=timeout,
        protocol=protocol,
        max_retries=0,
        config_path=os.devnull,
    )


DashScopeRequestTimeout = Annotated[
    float,
    Field(gt=0, allow_inf_nan=False, description="HTTP request timeout in seconds."),
]
_REQUEST_TIMEOUT = TypeAdapter(DashScopeRequestTimeout)


def _api_key_from_env() -> str:
    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        msg = (
            "DashScope API key is not provided. "
            "Please pass it as an argument or set the 'DASHSCOPE_API_KEY' environment variable."
        )
        raise ValueError(msg)
    return api_key


class DashScopeClientMixin(BaseModel):
    """Own one native client per Chat or Embedding connection, including direct use."""

    api_key: str = Field(
        default_factory=_api_key_from_env, repr=False, description="DashScope API key."
    )
    request_timeout: DashScopeRequestTimeout
    region: str = Field(default="cn-beijing", description="DashScope gateway region.")
    workspace: str | None = Field(default=None, description="Bailian workspace ID.")
    base_url: str | None = Field(default=None, description="DashScope HTTP API root.")
    _sdk_client: dashscope.Client | None = PrivateAttr(default=None)
    _client_lock: Any = PrivateAttr(default_factory=Lock)

    def __init__(self, api_key: str | None = None, **kwargs: Any) -> None:
        """Resolve an omitted API key when the connection is constructed."""
        if api_key:
            kwargs["api_key"] = api_key
        super().__init__(**kwargs)

    def _get_client(self) -> dashscope.Client:
        with self._client_lock:
            if self._sdk_client is None:
                self._sdk_client = create_client(
                    api_key=self.api_key,
                    base_url=self.base_url,
                    workspace=self.workspace,
                    region=self.region,
                    timeout=self.request_timeout,
                    protocol="native",
                )
            return self._sdk_client

    def close(self) -> None:
        """Release the connection pool after the last request."""
        with self._client_lock:
            if self._sdk_client is not None:
                try:
                    self._sdk_client.close()
                finally:
                    self._sdk_client = None


def split_request_options(
    kwargs: dict[str, Any], *, default_timeout: float
) -> dict[str, Any]:
    """Separate per-call transport settings from the remaining model parameters."""
    if kwargs.pop("stream", False):
        msg = "DashScope requires stream=False."
        raise ValueError(msg)
    unsupported = _UNSUPPORTED_CALL_OPTIONS.intersection(kwargs)
    if unsupported:
        msg = f"Unsupported DashScope call options: {', '.join(sorted(unsupported))}"
        raise ValueError(msg)
    timeout = kwargs.pop("request_timeout", None)
    names = {
        "api_key": "api_key",
        "workspace": "workspace",
        "headers": "extra_headers",
    }
    options = {
        target: kwargs.pop(source)
        for source, target in names.items()
        if source in kwargs
    }
    try:
        options["timeout"] = (
            default_timeout
            if timeout is None
            else _REQUEST_TIMEOUT.validate_python(timeout)
        )
    except ValueError as exc:
        msg = "request_timeout must be a positive, finite number of seconds."
        raise ValueError(msg) from exc
    return options


@contextmanager
def dashscope_request_errors(prefix: str) -> Iterator[None]:
    """Report service failures as RuntimeError with the SDK error as the cause."""
    try:
        yield
    except DashScopeError as exc:
        # Gateway failures carry an HTTP status, including business errors on 200.
        # The SDK leaves it unset for transport and response-validation errors.
        if exc.status_code is None:
            raise
        msg = f"{prefix}: {exc.message}"
        raise RuntimeError(msg) from exc


def usage_counters(usage: Any) -> dict[str, int | float | str]:
    """Return scalar usage fields from an SDK object or a response mapping.

    Boolean values are omitted. In Python they are integers, but they are not
    usage counters.
    """
    if usage is None:
        return {}
    raw = (
        usage.model_dump(exclude_unset=True, exclude_none=True)
        if hasattr(usage, "model_dump")
        else usage
    )
    if not isinstance(raw, dict):
        return {}
    return {
        key: value
        for key, value in raw.items()
        if isinstance(value, int | float | str)
        and not isinstance(value, bool)
        and (not isinstance(value, float) or math.isfinite(value))
    }
