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
from collections.abc import Collection, Mapping
from typing import Any
from urllib.parse import urlsplit

import dashscope

from flink_agents.api.chat_message import (
    Base64Source,
    MediaBlock,
    UnsupportedContentBlockError,
    UrlSource,
)

_HTTP_SCHEMES = frozenset({"http", "https"})


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
) -> dashscope.Client:
    """Create a client that does not read local config and does not retry."""
    return dashscope.Client(
        api_key=api_key,
        base_url=base_url,
        workspace=workspace,
        region=region,
        timeout=timeout,
        max_retries=0,
        config_path=os.devnull,
    )


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
