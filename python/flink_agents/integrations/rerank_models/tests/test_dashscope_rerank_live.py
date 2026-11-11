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
import os

import pytest

from flink_agents.api.chat_message import ImageBlock, TextBlock, VideoBlock
from flink_agents.integrations.rerank_models.dashscope_rerank_model import (
    DashScopeRerankModel,
)

pytestmark = pytest.mark.integration

_IMAGE = (
    "https://img.alicdn.com/imgextra/i3/"
    "O1CN01rdstgY1uiZWt8gqSL_!!6000000006071-0-tps-1970-356.jpg"
)
_VIDEO = (
    "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/"
    "zh-CN/20250107/lbcemt/new+video.mp4"
)
_DOCUMENTS = [
    TextBlock(text="A text reranking model orders candidate passages by relevance."),
    TextBlock(text="Quantum computing studies computation with qubits."),
    TextBlock(text="Pretrained language models are used to rank text."),
]
_api_key_available = "DASHSCOPE_API_KEY" in os.environ


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
@pytest.mark.parametrize("model_name", ["qwen3-rerank", "qwen3.7-text-rerank"])
def test_text_rerank_orders_the_relevant_document_first(model_name: str) -> None:
    """The passage that answers the query ranks first."""
    result = DashScopeRerankModel(model=model_name).rerank(
        TextBlock(text="What is a text reranking model?"),
        _DOCUMENTS,
        top_n=2,
    )

    assert result.results
    assert result.results[0].index == 0
    assert result.results[0].relevance_score >= result.results[-1].relevance_score
    assert all(hit.document is None for hit in result.results)


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_multimodal_rerank_accepts_text_image_and_video() -> None:
    """A text query can rank text, an image, and a video together."""
    result = DashScopeRerankModel(model="qwen3-vl-rerank").rerank(
        TextBlock(text="What is a text reranking model?"),
        [
            _DOCUMENTS[0],
            ImageBlock.from_url("image/jpeg", _IMAGE),
            VideoBlock.from_url("video/mp4", _VIDEO),
        ],
        top_n=1,
        return_documents=True,
        fps=0.1,
    )

    assert len(result.results) == 1
    assert all(hit.document is not None for hit in result.results)
    assert isinstance(result.usage.get("total_tokens"), int)
