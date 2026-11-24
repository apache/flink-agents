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
"""Typed result model returned by media model operations."""

from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

from flink_agents.api.chat_message import DataContentBlock

BlockT = TypeVar("BlockT", bound=DataContentBlock)


class MediaResult(BaseModel, Generic[BlockT]):
    """Typed result produced by any media model capability."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    content: list[BlockT] = Field(
        default_factory=list,
        description="Typed content blocks returned by the media model.",
    )
    expires_at_ms: int | None = Field(
        default=None,
        description="Epoch millisecond deadline when temporary result URLs expire.",
    )
    usage: dict[str, Any] = Field(
        default_factory=dict,
        description="Provider-reported usage counters and metrics.",
    )
    request_id: str | None = Field(
        default=None,
        description="Provider request identifier when available.",
    )
    metadata: dict[str, Any] = Field(
        default_factory=dict,
        description="Provider-specific extra metadata.",
    )
