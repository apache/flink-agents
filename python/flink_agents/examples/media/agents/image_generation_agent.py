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
"""Generate an image from each input prompt."""

from typing import TYPE_CHECKING, cast

from pydantic import BaseModel

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import UrlSource
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.media import AsyncJobModel
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.media import BaseImageGenerationModelSetup


class ImagePrompt(BaseModel):
    """A prompt and its correlation id."""

    id: str
    prompt: str


class ImageGenerationAgent(Agent):
    """Generate an image with the registered image generation model."""

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def process_input(event: Event, ctx: RunnerContext) -> None:
        """Submit an async image job, persist its handle, and wait for the result."""
        request = ImagePrompt.model_validate(InputEvent.from_event(event).input)
        image_model = cast(
            "BaseImageGenerationModelSetup",
            ctx.get_resource("image_model", ResourceType.MEDIA_MODEL),
        )
        if isinstance(image_model, AsyncJobModel):
            job = await ctx.durable_execute_async(image_model.submit, request.prompt)
            result = await ctx.durable_execute_async(image_model.wait, job, timeout=180)
        else:
            result = await ctx.durable_execute_async(
                image_model.generate, request.prompt, timeout=180
            )
        urls = [
            block.source.url
            for block in result.content
            if isinstance(block.source, UrlSource)
        ]
        ctx.send_event(
            OutputEvent(
                output={
                    "id": request.id,
                    "urls": urls,
                    "request_id": result.request_id,
                }
            )
        )
