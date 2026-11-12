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
from abc import ABC, abstractmethod
from typing import Any, Sequence

from pydantic import BaseModel, ConfigDict, Field
from typing_extensions import override

from flink_agents.api.chat_message import (
    DataContentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.resource import Resource, ResourceType

RerankQuery = TextBlock | ImageBlock
RerankDocument = TextBlock | ImageBlock | VideoBlock


class RerankHit(BaseModel):
    """One document after ranking, ordered by descending relevance."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    index: int = Field(description="Position of this document in the request.")
    relevance_score: float = Field(description="Provider relevance score.")
    document: DataContentBlock | None = Field(
        default=None,
        description="Original document when the caller asked for it.",
    )


class RerankResult(BaseModel):
    """Ranked documents from one synchronous rerank call."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    results: list[RerankHit] = Field(
        description="Documents ordered from most relevant to least relevant."
    )
    usage: dict[str, int | float | str] = Field(
        default_factory=dict,
        description="Provider usage counters, kept as reported.",
    )
    request_id: str | None = Field(
        default=None, description="Provider request identifier."
    )


class BaseRerankModel(Resource, ABC):
    """A model that ranks documents against one query.

    One instance binds a single ``model``. The call is synchronous: there is
    no submitted task to query or cancel.

    Methods never touch memory or emit events, so they can run inside a
    durable execution.
    """

    model: str = Field(description="Name of the model to call.")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.RERANK_MODEL

    @abstractmethod
    def rerank(
        self,
        query: RerankQuery,
        documents: Sequence[RerankDocument],
        *,
        top_n: int | None = None,
        return_documents: bool | None = None,
        instruct: str | None = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> RerankResult:
        """Rank ``documents`` against ``query``.

        Args:
            query: Text or, when the model accepts it, one image.
            documents: Candidates in request order. ``results[].index`` refers
                to this order.
            top_n: Maximum number of hits to return. None uses the resource
                configuration, or the provider default when not configured.
            return_documents: Whether hits include the original document.
                None uses the resource configuration, or the provider default
                when not configured.
            instruct: Ranking instruction. None uses the resource configuration,
                or the provider default when not configured.
            timeout: Seconds for this request. None uses the provider timeout.
            **kwargs: Provider parameters overriding the configured defaults.
        """
