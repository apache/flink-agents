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
from collections.abc import Mapping, Sequence
from typing import Any

import pytest

from flink_agents.api.chat_message import TextBlock
from flink_agents.api.rerank_models.rerank_model import (
    BaseRerankModel,
    RerankDocument,
    RerankHit,
    RerankQuery,
    RerankResult,
)
from flink_agents.api.resource import ResourceType


def test_rerank_result_json_roundtrip_keeps_the_document() -> None:
    result = RerankResult(
        results=[RerankHit(index=1, relevance_score=0.5, document=TextBlock(text="b"))],
        usage={"total_tokens": 3},
        request_id="req-1",
    )

    restored = RerankResult.model_validate(result.model_dump(mode="json"))

    assert restored == result
    assert restored.results[0].document == TextBlock(text="b")
    assert ResourceType.RERANK_MODEL.value == "rerank_model"


def test_base_rerank_model_rejects_blank_model() -> None:
    class _DummyRerank(BaseRerankModel):
        def rerank(
            self,
            query: RerankQuery,
            documents: Sequence[RerankDocument],
            *,
            top_n: int | None = None,
            return_documents: bool | None = None,
            model_parameters: Mapping[str, Any] | None = None,
            timeout: float | None = None,
        ) -> RerankResult:
            return RerankResult(results=[])

    for blank in ("", "   "):
        with pytest.raises(ValueError, match="non-blank"):
            _DummyRerank(model=blank)
