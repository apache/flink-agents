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
#  limitations under the License.
################################################################################
"""Describe an image with a DashScope chat model."""

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, ImageBlock, TextBlock
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.runner_context import RunnerContext


class DashScopeMultimodalAgent(Agent):
    """Send an image and text to the registered vision model."""

    @action(EventType.InputEvent)
    @staticmethod
    async def describe_image(event: Event, ctx: RunnerContext) -> None:
        """Emit a description of the input image."""
        image_url = InputEvent.from_event(event).input
        message = ChatMessage.user(
            [
                TextBlock(text="Describe this image."),
                ImageBlock.from_url("image/jpeg", image_url),
            ]
        )
        result = await ctx.chat("dashscope_vision", [message])
        ctx.send_event(OutputEvent(output=result.text))
