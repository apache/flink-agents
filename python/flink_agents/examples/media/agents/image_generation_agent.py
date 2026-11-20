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
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.media.media_provider import BaseMediaProvider


class ImagePrompt(BaseModel):
    """A prompt and its correlation id."""

    id: str
    prompt: str


class ImageGenerationAgent(Agent):
    """Generate an image with the registered DashScope provider."""

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Generate an image and emit its result with the input id."""
        request = ImagePrompt.model_validate(InputEvent.from_event(event).input)
        dashscope = cast(
            "BaseMediaProvider",
            ctx.get_resource("dashscope", ResourceType.MEDIA_PROVIDER),
        )
        result = dashscope.image("qwen-image-3.0").generate(
            request.prompt, model_parameters={"size": "512*512"}, timeout=180
        )
        ctx.send_event(
            OutputEvent(
                output={"id": request.id, "result": result.model_dump(mode="json")}
            )
        )
