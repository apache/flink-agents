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
"""Rank one question against a fixed list of text, image, and video results."""

from typing import TYPE_CHECKING, cast

from pydantic import BaseModel

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ImageBlock, TextBlock, VideoBlock
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.rerank_models.rerank_model import BaseRerankModel

_IMAGE = (
    "https://img.alicdn.com/imgextra/i3/"
    "O1CN01rdstgY1uiZWt8gqSL_!!6000000006071-0-tps-1970-356.jpg"
)
_VIDEO = (
    "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/"
    "zh-CN/20250107/lbcemt/new+video.mp4"
)
# The same result list is ranked for every question.
_RESULTS = [
    TextBlock(text="A text reranking model orders candidate passages by relevance."),
    ImageBlock.from_url("image/jpeg", _IMAGE),
    VideoBlock.from_url("video/mp4", _VIDEO),
]


class SearchQuestion(BaseModel):
    """A question to rank against ``_RESULTS``."""

    id: str
    text: str


class SearchRankingAgent(Agent):
    """Return the best result for a question.

    The job registers ``ranker``. Each result may be text, an image, or a
    video. The output names the chosen result by its position and block type.
    """

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Rank the shared result list and emit the top hit."""
        question = SearchQuestion.model_validate(InputEvent.from_event(event).input)
        model = cast(
            "BaseRerankModel",
            ctx.get_resource("ranker", ResourceType.RERANK_MODEL),
        )
        result = model.rerank(
            TextBlock(text=question.text),
            _RESULTS,
            top_n=1,
            return_documents=True,
            fps=0.1,
        )
        hit = result.results[0]
        ctx.send_event(
            OutputEvent(
                output={
                    "id": question.id,
                    "index": hit.index,
                    "kind": type(hit.document).__name__,
                }
            )
        )
