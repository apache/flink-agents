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
"""Make a product teaser: a key frame, a short clip, and a spoken tagline."""

from typing import TYPE_CHECKING, cast

from pydantic import BaseModel

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import (
    AudioBlock,
    ImageBlock,
    TextBlock,
    UrlSource,
    VideoBlock,
)
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.media_models.media_model import BaseMediaModel


class TeaserBrief(BaseModel):
    """One product teaser.

    ``look`` describes the key frame. ``motion`` describes the camera move.
    ``narration`` is the spoken tagline.
    """

    id: str
    look: str
    motion: str
    narration: str


class TeaserAgent(Agent):
    """Produce the key frame, clip, and voiceover for one product.

    The job registers ``still``, ``clip``, and ``narration``. The key frame is
    drawn from ``look`` and becomes the clip's first frame. ``motion`` is the
    video prompt. The voiceover reads ``narration``.
    """

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Generate the three parts of one product teaser."""
        brief = TeaserBrief.model_validate(InputEvent.from_event(event).input)
        picture = _still(ctx, brief.look)
        video = _clip(ctx, picture, brief.motion)
        audio = _narration(ctx, brief.narration)
        ctx.send_event(
            OutputEvent(
                output={
                    "id": brief.id,
                    "image": picture.media_type,
                    "video": video.media_type,
                    "speech": audio.media_type,
                }
            )
        )


def _still(ctx: RunnerContext, look: str) -> ImageBlock:
    result = _media(ctx, "still").generate(
        [TextBlock(text=look)],
        size="512*512",
        prompt_extend=False,
    )
    block = result.content[0]
    if not isinstance(block, ImageBlock) or not isinstance(block.source, UrlSource):
        msg = "Still generation did not return an image URL."
        raise TypeError(msg)
    return block


def _clip(ctx: RunnerContext, picture: ImageBlock, motion: str) -> VideoBlock:
    result = _media(ctx, "clip").generate(
        [
            TextBlock(text=motion),
            ImageBlock.from_url(picture.media_type, picture.source.url),
        ],
        video_media_roles=["first_frame"],
        resolution="480P",
        ratio="16:9",
        duration=3,
    )
    block = result.content[0]
    if not isinstance(block, VideoBlock):
        msg = "Clip generation did not return a video."
        raise TypeError(msg)
    return block


def _narration(ctx: RunnerContext, narration: str) -> AudioBlock:
    result = _media(ctx, "narration").generate([TextBlock(text=narration)])
    block = result.content[0]
    if not isinstance(block, AudioBlock):
        msg = "Narration did not return audio."
        raise TypeError(msg)
    return block


def _media(ctx: RunnerContext, name: str) -> "BaseMediaModel":
    return cast(
        "BaseMediaModel",
        ctx.get_resource(name, ResourceType.MEDIA_MODEL),
    )
